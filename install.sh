#!/usr/bin/env bash
set -euo pipefail

# SAIL CLI installer — downloads from GitHub Releases (no authentication required).
# Supports Linux (amd64) and macOS (arm64).
# Usage:
#   curl -fsSL https://raw.githubusercontent.com/standardapplied/sail/main/install.sh | bash
#   bash install.sh 0.11.2          # install specific version
#   bash install.sh v0.11.2         # also works with v prefix

GITHUB_REPO="standardapplied/sail"
INSTALL_DIR="/usr/local/bin"
BINARY="sail"

# --- Colors (disabled if not a TTY) ---
if [ -t 1 ]; then
  BOLD='\033[1m'
  GREEN='\033[0;32m'
  RED='\033[0;31m'
  RESET='\033[0m'
else
  BOLD='' GREEN='' RED='' RESET=''
fi

info()  { echo -e "${BOLD}$1${RESET}"; }
ok()    { echo -e "${GREEN}$1${RESET}"; }
fail()  { echo -e "${RED}$1${RESET}" >&2; exit 1; }

# --- Platform detection ---
OS="$(uname -s)"
ARCH="$(uname -m)"

case "$OS" in
  Linux)
    PLATFORM="linux-amd64"
    [ "$ARCH" = "x86_64" ] || fail "SAIL only supports x86_64 on Linux. Detected: $ARCH"
    CHECKSUM_CMD="sha256sum"
    ELF_CHECK=true
    ;;
  Darwin)
    PLATFORM="darwin-arm64"
    CHECKSUM_CMD="shasum -a 256"
    ELF_CHECK=false
    ;;
  *)
    fail "Unsupported OS: $OS. SAIL supports Linux and macOS."
    ;;
esac

BINARY_NAME="sail-${PLATFORM}"

# --- Resolve version ---
VERSION="${1:-latest}"

if [ "$VERSION" = "latest" ]; then
  info "Fetching latest version..."
  VERSION=$(curl -fsSL "https://api.github.com/repos/${GITHUB_REPO}/releases/latest" 2>/dev/null \
    | grep '"tag_name"' | head -1 | tr -d '", ' | sed 's/tag_name://' | sed 's/^v//')
  [ -n "$VERSION" ] || fail "Could not determine latest version from GitHub."
fi

# Normalize: strip leading 'v' for display, add for URL
case "$VERSION" in
  v*) TAG="$VERSION"; VERSION="${VERSION#v}" ;;
  *)  TAG="v$VERSION" ;;
esac

info "Installing SAIL $TAG ($PLATFORM)..."

# --- Download binary ---
TMPFILE=$(mktemp)
trap 'rm -f "$TMPFILE"' EXIT

DOWNLOAD_URL="https://github.com/${GITHUB_REPO}/releases/download/${TAG}/${BINARY_NAME}"
info "Downloading from GitHub Releases..."
HTTP_CODE=$(curl -fsSL -w '%{http_code}' "$DOWNLOAD_URL" -o "$TMPFILE" 2>/dev/null || true)
[ "$HTTP_CODE" = "200" ] || fail "Download failed (HTTP $HTTP_CODE). Check the version exists: $DOWNLOAD_URL"

# --- Verify checksum ---
info "Verifying checksum..."
CHECKSUM_URL="https://github.com/${GITHUB_REPO}/releases/download/${TAG}/${BINARY_NAME}.sha256"
EXPECTED=$(curl -fsSL "$CHECKSUM_URL" 2>/dev/null | awk '{print $1}' || true)
if [ -z "$EXPECTED" ]; then
  fail "Checksum not available for $BINARY_NAME. Refusing to install unverifiable release asset."
fi
ACTUAL=$($CHECKSUM_CMD "$TMPFILE" | awk '{print $1}')
if [ "$EXPECTED" != "$ACTUAL" ]; then
  fail "Checksum mismatch!\n  Expected: $EXPECTED\n  Actual:   $ACTUAL"
fi
ok "  Checksum verified."

# --- Validate binary format ---
if [ "$ELF_CHECK" = true ]; then
  ELF_MAGIC=$(head -c 4 "$TMPFILE" | od -A n -t x1 | tr -d ' \n')
  if [ "$ELF_MAGIC" != "7f454c46" ]; then
    fail "Downloaded file is not a valid Linux binary (expected ELF)."
  fi
else
  MACHO_MAGIC=$(head -c 4 "$TMPFILE" | od -A n -t x1 | tr -d ' \n')
  if [ "$MACHO_MAGIC" != "cffa edfe" ] && [ "$MACHO_MAGIC" != "cffaedfe" ]; then
    fail "Downloaded file is not a valid macOS binary (expected Mach-O)."
  fi
fi

chmod +x "$TMPFILE"

# --- Remove macOS quarantine if present ---
if [ "$OS" = "Darwin" ]; then
  xattr -d com.apple.quarantine "$TMPFILE" 2>/dev/null || true
fi

# --- Install ---
if [ -w "$INSTALL_DIR" ]; then
  mv "$TMPFILE" "$INSTALL_DIR/$BINARY"
else
  info "Installing to $INSTALL_DIR (requires sudo)..."
  sudo mv "$TMPFILE" "$INSTALL_DIR/$BINARY"
fi

# --- Verify ---
ok "Installed SAIL $TAG to $INSTALL_DIR/$BINARY"
echo

if [ "$OS" = "Darwin" ]; then
  info "Get started:"
  info "  sail client <host>  # point the CLI at your box (IP or SSH alias)"
  info "  sail spec list <project>"
else
  SUDO="sudo "
  [ "$(id -u)" -ne 0 ] || SUDO=""
  info "Get started:"
  info "  ${SUDO}sail init --as-main --handle <you>   # make this box the org's main"
  info "  ${SUDO}sail init --main <main>              # or join an existing main"
fi
