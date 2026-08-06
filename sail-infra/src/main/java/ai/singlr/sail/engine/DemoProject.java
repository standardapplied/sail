/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.engine;

import java.util.Map;

/**
 * The bundled demo project — an Outline wiki (Postgres + Redis) — shipped inside the binary so
 * {@code sail project demo} needs no network and no external repository. The definition is seeded
 * into the control-plane catalog on install/upgrade (see {@link DemoSeeder}); its workspace files
 * are materialised at provision time. This keeps a single concept: every project, the demo
 * included, lives in the database, never in GitHub.
 *
 * <p>The descriptor keeps the per-engineer placeholders ({@code ${GIT_NAME}}, {@code ${GIT_EMAIL}},
 * {@code ${SSH_PUBLIC_KEY}}) that {@code project demo} resolves against the local environment.
 */
public final class DemoProject {

  private DemoProject() {}

  /** Catalog name of the demo project. */
  public static final String NAME = "demo";

  /** The demo's {@code sail.yaml}, stored verbatim as the project definition. */
  public static final String DEFINITION =
      """
      name: demo
      description: "Outline wiki — open-source knowledge base with Postgres and Redis"

      resources:
        cpu: 2
        memory: 4GB
        disk: 20GB

      image: ubuntu/24.04

      runtimes:
        node: "22"

      git:
        name: ${GIT_NAME}
        email: ${GIT_EMAIL}
        auth: token

      repos:
        - url: https://github.com/outline/outline.git
          path: outline

      services:
        postgres:
          image: postgres:16
          ports: [5432]
          environment:
            POSTGRES_DB: outline
            POSTGRES_USER: dev
            POSTGRES_PASSWORD: dev
          volumes:
            - "pgdata:/var/lib/postgresql/data"
        redis:
          image: redis:7
          ports: [6379]

      ssh:
        user: dev
        authorized_keys:
          - ${SSH_PUBLIC_KEY}

      agent:
        type: claude-code
        auto_snapshot: true
        auto_branch: true
      """;

  private static final String OUTLINE_SETUP_SH =
      """
      #!/bin/bash
      set -euo pipefail

      export NVM_DIR="$HOME/.nvm"
      [ -s "$NVM_DIR/nvm.sh" ] && . "$NVM_DIR/nvm.sh"

      export PATH="$HOME/.local/bin:$PATH"
      corepack enable --install-directory ~/.local/bin yarn

      cd ~/workspace/outline

      echo "==> Outline Dev Environment Setup"
      echo

      # Generate secrets automatically
      SECRET_KEY=$(openssl rand -hex 32)
      UTILS_SECRET=$(openssl rand -hex 32)

      # Defaults — match sail.yaml Postgres config (superuser=dev, db=outline)
      PG_ADMIN="dev"
      DB_USER="dev"
      DB_PASS="dev"
      DB_NAME="outline"
      DB_HOST="localhost"
      DB_PORT="5432"
      REDIS_HOST="localhost"
      REDIS_PORT="6379"
      PORT="3000"

      # Let user customize if they want
      read -rp "  App port [${PORT}]: " input && PORT="${input:-$PORT}"
      read -rp "  Database name [${DB_NAME}]: " input && DB_NAME="${input:-$DB_NAME}"

      echo
      echo "==> Ensuring database exists..."
      podman exec postgres psql -U "${PG_ADMIN}" -d postgres -tc "SELECT 1 FROM pg_database WHERE datname='${DB_NAME}'" | grep -q 1 \\
        || podman exec postgres psql -U "${PG_ADMIN}" -d postgres -c "CREATE DATABASE ${DB_NAME};"
      echo "    Database '${DB_NAME}' ready"

      echo "==> Generating .env..."
      cat > .env << EOF
      NODE_ENV=development
      URL=http://localhost:${PORT}
      PORT=${PORT}
      WEB_CONCURRENCY=1

      SECRET_KEY=${SECRET_KEY}
      UTILS_SECRET=${UTILS_SECRET}

      DATABASE_URL=postgres://${DB_USER}:${DB_PASS}@${DB_HOST}:${DB_PORT}/${DB_NAME}
      DATABASE_CONNECTION_POOL_MIN=0
      DATABASE_CONNECTION_POOL_MAX=5
      PGSSLMODE=disable

      REDIS_URL=redis://${REDIS_HOST}:${REDIS_PORT}

      DEFAULT_LANGUAGE=en_US
      RATE_LIMITER_ENABLED=true
      RATE_LIMITER_REQUESTS=1000
      RATE_LIMITER_DURATION_WINDOW=60

      ENABLE_UPDATES=false
      DEBUG=http
      LOG_LEVEL=info
      EOF
      echo "    .env written"

      echo "==> Installing Node.js dependencies..."
      export COREPACK_ENABLE_AUTO_PIN=0
      yarn install --immutable

      echo "==> Building Outline (required before migrations)..."
      yarn build

      echo "==> Running database migrations..."
      yarn db:create --env=development 2>/dev/null || true
      yarn db:migrate --env=development

      echo
      echo "==> Done! Start the dev server with:"
      echo "    cd ~/workspace/outline && yarn dev"
      """;

  /** Workspace files materialised under {@code <project>/files/}, keyed by relative path. */
  public static Map<String, String> files() {
    return Map.of("outline/setup.sh", OUTLINE_SETUP_SH);
  }
}
