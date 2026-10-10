/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.FdeBoxes;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import picocli.CommandLine;

class FdeCommandTest {

  private static final String KEY_OPTIONS = "no-agent-forwarding,no-X11-forwarding,no-user-rc ";
  private static final SshPublicKey HOST_KEY =
      SshPublicKey.parse(TestSshKeys.ed25519("host", "root@box"));
  private static final SshPublicKey LAPTOP =
      SshPublicKey.parse(TestSshKeys.ed25519("laptop", "me@mac"));

  @TempDir Path tempDir;

  private final PrintStream originalOut = System.out;
  private final PrintStream originalErr = System.err;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final Keygen keygen = new Keygen();
  private String operator = "root";
  private boolean node;
  private String refusal;

  @BeforeEach
  void aBoxWithAHostKeyAndAMigratedControlPlane() throws IOException {
    System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
    System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
    Files.createDirectories(home());
    Files.writeString(hostKey(), HOST_KEY.line() + "\n");
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      new SchemaManager(db).migrate();
    }
  }

  @AfterEach
  void restoreStreams() {
    System.setOut(originalOut);
    System.setErr(originalErr);
  }

  private Path home() {
    return tempDir.resolve("home");
  }

  private Path hostKey() {
    return tempDir.resolve("ssh_host_ed25519_key.pub");
  }

  private Path authorizedKeys() {
    return home().resolve(".ssh").resolve("authorized_keys");
  }

  private Path workstationKey() {
    return home().resolve(".sail").resolve("workstation_key.pub");
  }

  private <T> T inDb(Function<Sqlite, T> work) {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      return work.apply(db);
    }
  }

  /** Runs {@code sail fde <args>} on this test's box, keeping a refusal's message. */
  private int fde(String... args) {
    out.reset();
    refusal = null;
    var command =
        new CommandLine(
            new FdeCommand(
                () ->
                    new FdeCommand.Box(
                        tempDir.resolve("sail.db"),
                        operator,
                        home(),
                        hostKey(),
                        workstationKey(),
                        keygen,
                        node)));
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal = failure.getMessage();
          return 1;
        });
    return command.execute(args);
  }

  private List<String> printed() {
    return out.toString(StandardCharsets.UTF_8).lines().toList();
  }

  private String printedCode() {
    return printed().stream()
        .filter(line -> line.startsWith(FdeCommand.CODE_PREFIX))
        .reduce((first, second) -> second)
        .orElseThrow();
  }

  private static Map<String, Object> decoded(String code) {
    assertTrue(code.startsWith("sail1."), code);
    var json = Base64.getUrlDecoder().decode(code.substring("sail1.".length()));
    return YamlUtil.parseMap(new String(json, StandardCharsets.UTF_8));
  }

  private static String mode(Path path) {
    try {
      return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String keyLine(SshPublicKey key, String handle) {
    return KEY_OPTIONS + key.type() + " " + key.blob() + " sail-mast:" + handle + "\n";
  }

  private List<TokenStore.TokenInfo> tokens() {
    return inDb(db -> new TokenStore(db).list());
  }

  private String handleOf(String token) {
    return inDb(
        db -> new TokenStore(db).validate(token).map(TokenStore.TokenInfo::fdeHandle).orElse(null));
  }

  /**
   * Whether nothing a pairing writes is on this box: no FDE, key line, workstation key or token.
   */
  private void assertNothingWritten(String handle) {
    assertTrue(inDb(db -> new FdeStore(db).byHandle(handle)).isEmpty(), "no FDE is added");
    assertFalse(Files.exists(home().resolve(".ssh")), "the operator's login is untouched");
    assertFalse(Files.exists(workstationKey()), "no workstation key is registered");
    assertEquals(List.of(), tokens());
    assertEquals(List.of(), keygen.commands, "no key is made for a pairing that is refused");
  }

  /**
   * What {@code ssh-keygen} does for a pairing: a fresh, unencrypted ed25519 pair in OpenSSH's own
   * formats at the path {@code -f} names.
   */
  private static final class Keygen implements ShellExec {

    private final List<List<String>> commands = new ArrayList<>();
    private final List<SshPublicKey> made = new ArrayList<>();
    private Result failure;

    @Override
    public Result exec(List<String> command) throws IOException {
      commands.add(List.copyOf(command));
      if (failure != null) {
        return failure;
      }
      var file = Path.of(command.getLast());
      var comment = command.get(command.indexOf("-C") + 1);
      try {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var encodedPublic = pair.getPublic().getEncoded();
        var encodedPrivate = pair.getPrivate().getEncoded();
        var publicKey =
            Arrays.copyOfRange(encodedPublic, encodedPublic.length - 32, encodedPublic.length);
        var seed =
            Arrays.copyOfRange(encodedPrivate, encodedPrivate.length - 32, encodedPrivate.length);
        var line =
            "ssh-ed25519 "
                + Base64.getEncoder().encodeToString(publicBlob(publicKey))
                + " "
                + comment;
        Files.writeString(file, privateKeyFile(publicKey, seed, comment));
        Files.writeString(file.resolveSibling(file.getFileName() + ".pub"), line + "\n");
        made.add(SshPublicKey.parse(line));
        return new Result(0, "", "");
      } catch (GeneralSecurityException e) {
        throw new IOException(e);
      }
    }

    @Override
    public Result exec(List<String> command, Path workDir, Duration timeout) throws IOException {
      return exec(command);
    }

    @Override
    public boolean isDryRun() {
      return false;
    }

    private static byte[] publicBlob(byte[] publicKey) {
      var blob = new ByteArrayOutputStream();
      sshString(blob, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
      sshString(blob, publicKey);
      return blob.toByteArray();
    }

    private static String privateKeyFile(byte[] publicKey, byte[] seed, String comment) {
      var secret = new ByteArrayOutputStream();
      secret.writeBytes(new byte[] {1, 2, 3, 4, 1, 2, 3, 4});
      sshString(secret, "ssh-ed25519".getBytes(StandardCharsets.US_ASCII));
      sshString(secret, publicKey);
      var keyPair = new ByteArrayOutputStream();
      keyPair.writeBytes(seed);
      keyPair.writeBytes(publicKey);
      sshString(secret, keyPair.toByteArray());
      sshString(secret, comment.getBytes(StandardCharsets.UTF_8));
      for (var pad = 1; secret.size() % 8 != 0; pad++) {
        secret.write(pad);
      }
      var key = new ByteArrayOutputStream();
      key.writeBytes("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII));
      sshString(key, "none".getBytes(StandardCharsets.US_ASCII));
      sshString(key, "none".getBytes(StandardCharsets.US_ASCII));
      sshString(key, new byte[0]);
      key.writeBytes(ByteBuffer.allocate(4).putInt(1).array());
      sshString(key, publicBlob(publicKey));
      sshString(key, secret.toByteArray());
      return "-----BEGIN OPENSSH PRIVATE KEY-----\n"
          + Base64.getMimeEncoder(70, new byte[] {'\n'}).encodeToString(key.toByteArray())
          + "\n-----END OPENSSH PRIVATE KEY-----\n";
    }

    private static void sshString(ByteArrayOutputStream to, byte[] bytes) {
      to.writeBytes(ByteBuffer.allocate(4).putInt(bytes.length).array());
      to.writeBytes(bytes);
    }
  }

  /**
   * The public key blob inside an OpenSSH private key file, having checked the file is one and that
   * no passphrase guards it.
   */
  private static String publicBlobOfUnencryptedPrivateKey(String file) {
    var lines = file.lines().toList();
    assertEquals("-----BEGIN OPENSSH PRIVATE KEY-----", lines.getFirst());
    assertEquals("-----END OPENSSH PRIVATE KEY-----", lines.getLast());
    assertTrue(file.endsWith("\n"), "OpenSSH refuses a key file with no final newline");
    var bytes =
        ByteBuffer.wrap(
            Base64.getDecoder().decode(String.join("", lines.subList(1, lines.size() - 1))));
    var magic = new byte[15];
    bytes.get(magic);
    assertEquals("openssh-key-v1\0", new String(magic, StandardCharsets.US_ASCII));
    assertEquals("none", new String(sshString(bytes), StandardCharsets.US_ASCII), "the cipher");
    assertEquals("none", new String(sshString(bytes), StandardCharsets.US_ASCII), "the kdf");
    assertEquals(0, sshString(bytes).length);
    assertEquals(1, bytes.getInt());
    return Base64.getEncoder().encodeToString(sshString(bytes));
  }

  private static byte[] sshString(ByteBuffer from) {
    var bytes = new byte[from.getInt()];
    from.get(bytes);
    return bytes;
  }

  @Test
  void pairingANewFdeOnAFreshBoxPrintsOneCodeThatIsTheWholeSetup() throws Exception {
    var exit = fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com");

    assertEquals(0, exit, refusal);
    var ada = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertEquals("member", ada.role());
    assertEquals("ada@x.com", ada.email());
    var key = keygen.made.getFirst();
    assertEquals(keyLine(key, "ada"), Files.readString(authorizedKeys()));
    assertEquals("rwx------", mode(home().resolve(".ssh")));
    assertEquals("rw-------", mode(authorizedKeys()));
    assertEquals(key.line() + "\n", Files.readString(workstationKey()));
    assertEquals("rw-r--r--", mode(workstationKey()));
    var token = tokens().getFirst();
    assertEquals(1, tokens().size());
    assertEquals("mast-ada", token.name());
    assertEquals("member", token.role());
    assertEquals("ada", token.fdeHandle());
    var lifetime = Duration.between(Instant.now(), Instant.parse(token.expiresAt()));
    assertEquals(364, lifetime.toDays(), "a code lives a year unless the operator says otherwise");
    var pairing = inDb(db -> new FdePairingStore(db).find(ada.id())).orElseThrow();
    assertEquals(
        new FdePairingStore.Pairing(ada.id(), key.line(), "mast-ada", pairing.createdAt()),
        pairing);

    var code = printedCode();
    assertEquals(
        List.of(
            "  ✓ FDE added: ada (member)",
            "  ✓ Paired ada: Mast logs in as root@34.1.2.3. The code expires "
                + token.expiresAt()
                + ".",
            "  ✓ Workstation key set.",
            "",
            "  Paste this code into Mast. It is a secret, and it is shown once:",
            "",
            code),
        printed());
    assertEquals("", err.toString(StandardCharsets.UTF_8));
    assertTrue(code.matches("sail1\\.[A-Za-z0-9_-]+"), "base64url with no padding");

    var fields = decoded(code);
    var privateKey = (String) fields.get("key");
    var apiToken = (String) fields.get("token");
    var expected = new LinkedHashMap<String, Object>();
    expected.put("v", 1);
    expected.put("handle", "ada");
    expected.put("email", "ada@x.com");
    expected.put("host", "34.1.2.3");
    expected.put("port", 22);
    expected.put("user", "root");
    expected.put("host_key", HOST_KEY.type() + " " + HOST_KEY.blob());
    expected.put("key", privateKey);
    expected.put("token", apiToken);
    expected.put("server", "http://127.0.0.1:7070");
    assertEquals(expected, fields);
    assertEquals(List.copyOf(expected.keySet()), List.copyOf(fields.keySet()));
    assertEquals(key.blob(), publicBlobOfUnencryptedPrivateKey(privateKey));
    assertFalse(apiToken.startsWith("sess_"), "Mast reads it as an API token");
    assertEquals("ada", handleOf(apiToken));

    var keygenCommand = keygen.commands.getFirst();
    assertEquals(1, keygen.commands.size());
    assertEquals(
        List.of("ssh-keygen", "-t", "ed25519", "-N", "", "-C", "sail-mast:ada", "-f"),
        keygenCommand.subList(0, 8));
    assertFalse(
        Files.exists(Path.of(keygenCommand.getLast()).getParent()),
        "the directory the pair was made in is gone");
    try (var files = Files.walk(tempDir)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        var content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        assertFalse(content.contains("PRIVATE KEY"), file + " keeps the private key");
        assertFalse(content.contains(apiToken), file + " keeps the token");
      }
    }
  }

  @Test
  void pairingAgainReplacesTheLineTheTokenAndTheRecord() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var firstToken = (String) decoded(printedCode()).get("token");
    inDb(
        db -> {
          Acting.system(
              () -> {
                new ProjectStore(db).upsert("acme", "name: acme\n");
                new ProjectStore(db).upsert("web", "name: web\n");
              });
          return null;
        });

    var exit = fde("pair", "ada", "--host", "box.acme.dev");

    assertEquals(0, exit, refusal);
    var second = keygen.made.getLast();
    assertEquals(2, keygen.made.size());
    assertEquals(keyLine(second, "ada"), Files.readString(authorizedKeys()));
    assertEquals(
        second.line() + "\n",
        Files.readString(workstationKey()),
        "the key of its own earlier pairing is replaced without --as-workstation-key");
    var fields = decoded(printedCode());
    assertEquals("ada@x.com", fields.get("email"), "an FDE this box holds keeps its email");
    assertEquals("box.acme.dev", fields.get("host"));
    assertNull(handleOf(firstToken), "the first code's token is revoked");
    assertEquals("ada", handleOf((String) fields.get("token")));
    assertEquals(List.of("mast-ada"), tokens().stream().map(TokenStore.TokenInfo::name).toList());
    var ada = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertEquals(
        second.line(),
        inDb(db -> new FdePairingStore(db).find(ada.id())).orElseThrow().publicKey());
    assertEquals(
        List.of(
            "  ✓ Unpaired ada first: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked.",
            "  ✓ Paired ada: Mast logs in as root@box.acme.dev. The code expires "
                + tokens().getFirst().expiresAt()
                + ".",
            "  ✓ Workstation key set.",
            "",
            "  Paste this code into Mast. It is a secret, and it is shown once:",
            "",
            printedCode(),
            "",
            "  Containers of acme, web trust this key only after their next 'sail project"
                + " apply'."),
        printed());
  }

  @ParameterizedTest
  @CsvSource({"me@mac, ' (me@mac)'", ",''"})
  void pairingWhereTheWorkstationKeyIsAnothersIsRefusedNamingItAndWritesNothing(
      String comment, String named) throws Exception {
    var registered = SshPublicKey.parse(TestSshKeys.ed25519("laptop", comment));
    Files.createDirectories(workstationKey().getParent());
    Files.writeString(workstationKey(), registered.line() + "\n");

    var exit = fde("pair", "bob", "--host", "34.1.2.3", "--email", "bob@x.com");

    assertEquals(1, exit);
    assertEquals(
        "This box's containers trust the workstation key "
            + registered.fingerprint()
            + named
            + ", and they trust one key. Pass --as-workstation-key to replace it with bob's.",
        refusal);
    assertEquals(registered.line() + "\n", Files.readString(workstationKey()));
    Files.delete(workstationKey());
    assertNothingWritten("bob");
  }

  @Test
  void asWorkstationKeyReplacesAnothersKeyAndNamesItsOwner() throws Exception {
    Files.createDirectories(workstationKey().getParent());
    Files.writeString(workstationKey(), LAPTOP.line() + "\n");

    var exit =
        fde("pair", "bob", "--host", "34.1.2.3", "--email", "bob@x.com", "--as-workstation-key");

    assertEquals(0, exit, refusal);
    assertEquals(keygen.made.getFirst().line() + "\n", Files.readString(workstationKey()));
    assertEquals(
        "  ✓ Workstation key replaced: it was " + LAPTOP.fingerprint() + " (me@mac).",
        printed().get(2));
  }

  @Test
  void unpairRemovesTheLineTheTokenAndTheRecordAndIsIdempotent() throws Exception {
    Files.createDirectories(authorizedKeys().getParent());
    Files.writeString(authorizedKeys(), LAPTOP.line() + "\n");
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var token = (String) decoded(printedCode()).get("token");
    var workstation = Files.readString(workstationKey());

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked."),
        printed());
    assertEquals(LAPTOP.line() + "\n", Files.readString(authorizedKeys()));
    assertNull(handleOf(token));
    assertEquals(List.of(), tokens());
    var ada = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertTrue(inDb(db -> new FdePairingStore(db).find(ada.id())).isEmpty());
    assertEquals(workstation, Files.readString(workstationKey()), "replacing it is a separate act");

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(List.of("  ada has no pairing on this box; nothing to do."), printed());
    assertEquals(LAPTOP.line() + "\n", Files.readString(authorizedKeys()));
  }

  @Test
  void unpairSaysOnlyWhatItRemoved() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    assertEquals(
        0, fde("pair", "bob", "--host", "34.1.2.3", "--email", "b@x.com", "--as-workstation-key"));
    inDb(db -> new TokenStore(db).revoke("mast-ada"));
    var bob = inDb(db -> new FdeStore(db).byHandle("bob")).orElseThrow();
    inDb(db -> new FdePairingStore(db).remove(bob.id()));

    assertEquals(0, fde("unpair", "ada"), refusal);
    assertEquals(
        List.of("  ✓ Unpaired ada: 1 key line removed from root's authorized_keys."), printed());

    assertEquals(0, fde("unpair", "bob"), refusal);
    assertEquals(
        List.of("  ✓ Unpaired bob: 1 key line removed from root's authorized_keys."), printed());
    assertEquals("", Files.readString(authorizedKeys()));
    assertEquals(
        List.of("mast-bob"),
        tokens().stream().map(TokenStore.TokenInfo::name).toList(),
        "a token no pairing names is not this command's to revoke");

    assertEquals(1, fde("unpair", "not a handle"));
    assertEquals(
        "Invalid FDE handle: 'not a handle'. Must match [A-Za-z0-9][A-Za-z0-9._-]*, max 63"
            + " characters.",
        refusal);
  }

  @Test
  void removingAnFdeRevokesItsPairingAlongWithItsOtherCredentials() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var token = (String) decoded(printedCode()).get("token");

    assertEquals(0, fde("rm", "ada", "--force"), refusal);

    assertEquals(
        List.of(
            "  ✓ FDE removed: ada",
            "  Owned tokens, SSH keys, sessions, passkeys, and enrollment tickets are revoked.",
            "  ✓ Its Mast pairing is revoked: 1 key line removed from root's authorized_keys."),
        printed());
    assertEquals("", Files.readString(authorizedKeys()));
    assertNull(handleOf(token));
    assertTrue(inDb(db -> new FdeStore(db).byHandle("ada")).isEmpty());
    assertEquals(
        0L,
        inDb(db -> db.queryOne("SELECT count(*) FROM fde_pairings", row -> row.integer(0)))
            .orElseThrow());
  }

  @Test
  void aRefusedRemovalOfTheLastAdminLeavesItsPairingWhole() throws Exception {
    assertEquals(
        0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com", "--role", "admin"));
    var token = (String) decoded(printedCode()).get("token");
    var line = Files.readString(authorizedKeys());

    assertEquals(1, fde("rm", "ada", "--force"));

    assertEquals(
        "'ada' is the last active admin FDE. Add another admin first: sail fde add <handle>"
            + " --role admin",
        refusal);
    assertEquals(line, Files.readString(authorizedKeys()));
    assertEquals("ada", handleOf(token));
    assertEquals("admin", tokens().getFirst().role(), "the code carries its FDE's role");
  }

  @Test
  void removingAnFdeThatWasNeverPairedSaysNothingOfAPairing() {
    inDb(db -> new FdeStore(db).add("bob", null, null, "member"));

    assertEquals(0, fde("rm", "bob", "--force"), refusal);

    assertEquals(
        List.of(
            "  ✓ FDE removed: bob",
            "  Owned tokens, SSH keys, sessions, passkeys, and enrollment tickets are revoked."),
        printed());
    assertFalse(Files.exists(home().resolve(".ssh")));

    assertEquals(1, fde("rm", "bob", "--force"));
    assertEquals("Unknown FDE 'bob'.", refusal);
  }

  @Test
  void aNodeRefusesToPairOrRemoveWithTheRosterSentence() {
    node = true;

    assertEquals(1, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Pairing an FDE"), refusal);
    assertNothingWritten("ada");

    assertEquals(1, fde("rm", "ada", "--force"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Removing an FDE"), refusal);
  }

  @Test
  void theSshGatewaysAccountNeitherMakesNorRevokesAPairing() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var token = (String) decoded(printedCode()).get("token");
    var line = Files.readString(authorizedKeys());
    inDb(db -> new FdeStore(db).add("bob", null, "bob@x.com", "member"));
    operator = "sail";
    keygen.commands.clear();

    assertEquals(1, fde("pair", "bob", "--host", "34.1.2.3", "--as-workstation-key"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sail fde pair bob' on the host, as the account sail-api runs as.",
        refusal);
    assertEquals(1, fde("unpair", "ada"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sail fde unpair ada' on the host, as the account sail-api runs as.",
        refusal);
    assertEquals(1, fde("rm", "ada", "--force"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sail fde rm ada' on the host, as the account sail-api runs as.",
        refusal);

    assertEquals(line, Files.readString(authorizedKeys()), "the gateway's file takes no login");
    assertEquals(List.of(), keygen.commands);
    assertEquals("ada", handleOf(token), "a removal that cannot revoke the login removes nothing");
    assertEquals(List.of("mast-ada"), tokens().stream().map(TokenStore.TokenInfo::name).toList());
  }

  @Test
  void theSshGatewayRemovesAnFdeThatWasNeverPairedLeavingItsOwnKeyFileAlone() throws Exception {
    inDb(db -> new FdeStore(db).add("bob", null, null, "member"));
    var forced =
        "command=\"sail _gateway --fde eve\",restrict " + LAPTOP.type() + " x sail-mast:bob\n";
    Files.createDirectories(authorizedKeys().getParent());
    Files.writeString(authorizedKeys(), forced);
    operator = "sail";

    assertEquals(0, fde("rm", "bob", "--force"), refusal);

    assertEquals(
        List.of(
            "  ✓ FDE removed: bob",
            "  Owned tokens, SSH keys, sessions, passkeys, and enrollment tickets are revoked."),
        printed());
    assertEquals(forced, Files.readString(authorizedKeys()));
  }

  @Test
  void aBoxWithNoHostKeyIsRefusedNamingTheFileAndWritesNothing() throws Exception {
    Files.delete(hostKey());

    assertEquals(1, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));

    assertEquals(
        "This box has no SSH host key at "
            + hostKey()
            + ", and a connect code pins it. Generate one with 'sudo ssh-keygen -A' and pair"
            + " again.",
        refusal);
    assertNothingWritten("ada");
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      quoteCharacter = '"',
      value = {
        "pair ada --email ada@x.com"
            + "|Pass --host <ip-or-dns>: a box cannot know the address Mast reaches it at.",
        "pair ada --host root@box --email ada@x.com"
            + "|Invalid host: 'root@box'. Must be a DNS name or an IP address, with no port.",
        "pair ada --host box --email ada"
            + "|Invalid email: 'ada'. Must be one address, like ada@example.com.",
        "pair a/da --host box --email ada@x.com"
            + "|Invalid FDE handle: 'a/da'. Must match [A-Za-z0-9][A-Za-z0-9._-]*, max 63"
            + " characters.",
        "pair ada --host box"
            + "|This box holds no email for 'ada', and a connect code names one. Pass --email"
            + " <email>.",
        "pair ada --host box --email ada@x.com --ttl-days 0"
            + "|--ttl-days must be a positive number of days.",
        "pair ada --host box --email ada@x.com --ttl-days 30 --no-expiry"
            + "|Pass --ttl-days or --no-expiry, not both."
      })
  void whatAPairingIsGivenIsCheckedBeforeAnythingIsWritten(String arguments, String message) {
    assertEquals(1, fde(arguments.split(" ")));

    assertEquals(message, refusal);
    assertNothingWritten("ada");
  }

  @Test
  void aNameOrARoleThatIsNoneIsRefusedAndWritesNothing() {
    assertEquals(
        1, fde("pair", "ada", "--host", "box", "--email", "ada@x.com", "--name", "two\nlines"));
    assertEquals(
        "Invalid name: must be 1 to 128 characters on one line, with no control characters.",
        refusal);
    assertNothingWritten("ada");

    assertEquals(1, fde("pair", "ada", "--host", "box", "--email", "ada@x.com", "--role", "root"));
    assertTrue(refusal.startsWith("Invalid role: root. Must be one of ["), refusal);
    assertTrue(inDb(db -> new FdeStore(db).byHandle("ada")).isEmpty());
    assertFalse(Files.exists(home().resolve(".ssh")));
    assertFalse(Files.exists(workstationKey()));
    assertEquals(List.of(), tokens());
  }

  @Test
  void anFdeThisBoxHoldsKeepsItsRoleAndEmailUnlessTheOptionsSayOtherwise() {
    inDb(db -> new FdeStore(db).add("ada", "Ada", "ada@x.com", "admin"));

    assertEquals(0, fde("pair", "ada", "--host", "box", "--no-expiry"), refusal);

    var kept = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertEquals(
        List.of("Ada", "ada@x.com", "admin"),
        List.of(kept.displayName(), kept.email(), kept.role()));
    assertEquals("admin", tokens().getFirst().role());
    assertNull(tokens().getFirst().expiresAt());
    assertEquals(
        "  ✓ Paired ada: Mast logs in as root@box. The code never expires.", printed().getFirst());
    assertEquals("ada@x.com", decoded(printedCode()).get("email"));

    assertEquals(
        0,
        fde(
            "pair",
            "ada",
            "--host",
            "box",
            "--email",
            "a@y.org",
            "--name",
            "Ada L",
            "--role",
            "viewer",
            "--ttl-days",
            "30"),
        refusal);

    var changed = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertEquals(
        List.of("Ada L", "a@y.org", "viewer"),
        List.of(changed.displayName(), changed.email(), changed.role()));
    assertEquals(kept.id(), changed.id());
    assertEquals("viewer", tokens().getFirst().role());
    var lifetime = Duration.between(Instant.now(), Instant.parse(tokens().getFirst().expiresAt()));
    assertEquals(29, lifetime.toDays());
    assertEquals("a@y.org", decoded(printedCode()).get("email"));
  }

  @Test
  void aTokenOfThePairingsNameMadeByHandIsReplaced() {
    var byHand = inDb(db -> new TokenStore(db).create("mast-ada", "admin", null, null)).token();

    assertEquals(0, fde("pair", "ada", "--host", "box", "--email", "ada@x.com"), refusal);

    assertNull(handleOf(byHand));
    assertEquals(List.of("member"), tokens().stream().map(TokenStore.TokenInfo::role).toList());
  }

  @Test
  void aDisabledFdeIsNotPaired() {
    inDb(
        db -> {
          new FdeStore(db).replicate("ada", null, "ada@x.com", "member", "disabled", null);
          return null;
        });

    assertEquals(1, fde("pair", "ada", "--host", "box"));

    assertEquals(
        "FDE 'ada' is disabled on this box, so a code for it would open nothing. Remove it with"
            + " 'sail fde rm ada' and pair again.",
        refusal);
    assertFalse(Files.exists(home().resolve(".ssh")));
    assertEquals(List.of(), keygen.commands);
  }

  @Test
  void aKeyThatCannotBeMadeFailsThePairingBeforeAnythingIsWritten() {
    keygen.failure = new ShellExec.Result(1, "", "ssh-keygen: not found\n");

    assertEquals(1, fde("pair", "ada", "--host", "box", "--email", "ada@x.com"));

    assertEquals("ssh-keygen failed: ssh-keygen: not found", refusal);
    assertFalse(Files.exists(Path.of(keygen.commands.getFirst().getLast()).getParent()));
    keygen.commands.clear();
    assertNothingWritten("ada");
  }

  @Test
  void theCodeNamesTheAccountTheCommandRunsAsAndItsHomeTakesTheKeys() throws Exception {
    operator = "ubuntu";

    assertEquals(0, fde("pair", "ada", "--host", "box", "--email", "ada@x.com"), refusal);

    assertEquals("ubuntu", decoded(printedCode()).get("user"));
    assertEquals(keyLine(keygen.made.getFirst(), "ada"), Files.readString(authorizedKeys()));
    assertEquals(keygen.made.getFirst().line() + "\n", Files.readString(workstationKey()));
    assertEquals(
        "  ✓ Paired ada: Mast logs in as ubuntu@box. The code expires "
            + tokens().getFirst().expiresAt()
            + ".",
        printed().get(1));
  }

  @Test
  void onARealBoxTheOperatorIsTheAccountSailRunsAsAndItsHome() {
    var box = FdeCommand.Box.here();

    assertEquals(System.getProperty("user.name"), box.operator());
    assertEquals(Path.of(System.getProperty("user.home")), box.home());
    assertEquals(Path.of("/etc/ssh/ssh_host_ed25519_key.pub"), box.hostKey());
    assertEquals(SailPaths.workstationPublicKeyPath(), box.workstationKey());
    assertEquals(SailPaths.controlPlaneDb(), box.database());
    assertFalse(box.shell().isDryRun());
    assertEquals(HostSync.isNode(HostSync.config()), box.node());
  }

  private static WebauthnCredentialStore.Credential credential(String id, String label) {
    return new WebauthnCredentialStore.Credential(
        id.getBytes(StandardCharsets.UTF_8),
        "fde-1",
        new byte[] {1},
        -7,
        0,
        null,
        false,
        false,
        label,
        "2026-07-06T10:15:30Z",
        null);
  }

  @Test
  void rosterEditOnNodeMessageNamesTheActionAndPointsAtMain() {
    var message = FdeCommand.rosterEditOnNodeMessage("Adding an FDE");
    assertTrue(message.contains("Adding an FDE"));
    assertTrue(message.contains("main devbox"));
    assertTrue(message.contains("Run this on main"));
    assertTrue(message.contains("overwritten on the next sync"));
  }

  @Test
  void releasingAnFdesBoxLetsTheNextBoxThatSyncsAsItBeRecorded() {
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      var boxes = new FdeBoxes(db);
      boxes.claim("ada", "devbox");

      var released = FdeCommand.ReleaseBox.release(boxes, "ada", false);

      assertTrue(released.contains("Released box 'devbox' for ada"), released);
      assertTrue(boxes.claim("ada", "laptop").isEmpty(), "the next box is recorded");
      assertTrue(FdeCommand.ReleaseBox.release(boxes, "bob", false).contains("No box"));
      var json = YamlUtil.parseMap(FdeCommand.ReleaseBox.release(boxes, "ada", true));
      assertEquals(true, json.get("released"));
      assertEquals("laptop", json.get("box"));
    }
  }

  @Test
  void passkeyNoMatchMessageListsRegisteredCredentials() {
    var credentials = List.of(credential("alpha", "macbook"), credential("bravo", null));
    var message = FdeCommand.Passkey.noMatchMessage("uday", "zz", credentials);
    assertTrue(message.contains("No passkey of 'uday' matches 'zz'"));
    assertTrue(message.contains(Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8))));
    assertTrue(message.contains("macbook"));
    assertTrue(message.contains("passkey · 2026-07-06"), "unlabeled rows show the default label");
  }

  @Test
  void passkeyNoMatchMessagePointsAtEnrollWhenNoneRegistered() {
    var message = FdeCommand.Passkey.noMatchMessage("uday", "zz", List.of());
    assertTrue(message.contains("'uday' has no passkeys"));
    assertTrue(message.contains("sail fde enroll uday"));
  }

  @Test
  void enrollTicketJsonCarriesTicketFdeAndExpiry() {
    var ticket = new EnrollmentTickets.Ticket("tkt_abc", "uday", "2026-07-07T10:15:30Z");
    var json = YamlUtil.dumpJson(FdeCommand.Enroll.ticketJson(ticket, "https://sail.acme.dev"));
    var parsed = YamlUtil.parseMap(json);
    assertEquals("tkt_abc", parsed.get("ticket"));
    assertEquals("uday", parsed.get("fde"));
    assertEquals("2026-07-07T10:15:30Z", parsed.get("expires_at"));
    assertEquals("https://sail.acme.dev/enroll?ticket=tkt_abc", parsed.get("enroll_url"));
  }

  @Test
  void enrollTicketJsonOmitsEnrollUrlWithoutAConfiguredOrigin() {
    var ticket = new EnrollmentTickets.Ticket("tkt_abc", "uday", "2026-07-07T10:15:30Z");
    var parsed = YamlUtil.parseMap(YamlUtil.dumpJson(FdeCommand.Enroll.ticketJson(ticket, null)));
    assertFalse(parsed.containsKey("enroll_url"));
  }

  @Test
  void passkeyAmbiguousMessageListsEveryCandidate() {
    var candidates = List.of(credential("alpha-one", "a"), credential("alpha-two", "b"));
    var message = FdeCommand.Passkey.ambiguousMessage("uday", "YWxwaGE", candidates);
    assertTrue(message.contains("'YWxwaGE' matches 2 passkeys of 'uday'"));
    assertTrue(message.contains("use a longer prefix"));
    assertTrue(
        message.contains(Passkeys.encodeId("alpha-one".getBytes(StandardCharsets.UTF_8))),
        "candidates list full ids so one is always copy-pasteable");
    assertTrue(message.contains(Passkeys.encodeId("alpha-two".getBytes(StandardCharsets.UTF_8))));
  }
}
