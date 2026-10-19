/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.singlr.sail.auth.EnrollmentTickets;
import ai.singlr.sail.auth.Passkeys;
import ai.singlr.sail.config.YamlUtil;
import ai.singlr.sail.engine.FileMutexTest;
import ai.singlr.sail.engine.OperatorCommand;
import ai.singlr.sail.engine.PairingTrust;
import ai.singlr.sail.engine.SailPaths;
import ai.singlr.sail.engine.ShellExec;
import ai.singlr.sail.identity.Acting;
import ai.singlr.sail.identity.ProjectScope;
import ai.singlr.sail.ssh.SshPublicKey;
import ai.singlr.sail.ssh.TestSshKeys;
import ai.singlr.sail.store.AuthSessionStore;
import ai.singlr.sail.store.ChangeLog;
import ai.singlr.sail.store.DataMigrations;
import ai.singlr.sail.store.FdeBoxes;
import ai.singlr.sail.store.FdePairingStore;
import ai.singlr.sail.store.FdeSshKeyStore;
import ai.singlr.sail.store.FdeStore;
import ai.singlr.sail.store.ProjectStore;
import ai.singlr.sail.store.SchemaManager;
import ai.singlr.sail.store.Sqlite;
import ai.singlr.sail.store.TokenStore;
import ai.singlr.sail.store.WebauthnCredentialStore;
import ai.singlr.sail.webauthn.RegisteredCredential;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;
import picocli.CommandLine.Help.Ansi;

class FdeCommandTest {

  private static final String KEY_OPTIONS = "no-agent-forwarding,no-X11-forwarding,no-user-rc";
  private static final DateTimeFormatter SSHD_UTC =
      DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final SshPublicKey HOST_KEY =
      SshPublicKey.parse(TestSshKeys.ed25519("host", "root@box"));
  private static final SshPublicKey LAPTOP =
      SshPublicKey.parse(TestSshKeys.ed25519("laptop", "me@mac"));

  @TempDir Path tempDir;

  private final PrintStream originalOut = System.out;
  private final PrintStream originalErr = System.err;
  private final InputStream originalIn = System.in;
  private final BooleanSupplier originalTerminal = ConsoleHelper.terminal;
  private final ByteArrayOutputStream out = new ByteArrayOutputStream();
  private final ByteArrayOutputStream err = new ByteArrayOutputStream();
  private final Keygen keygen = new Keygen();
  private String operator = "root";
  private String main;
  private String refusal;
  private String handle = "ada";

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
    System.setIn(originalIn);
    ConsoleHelper.terminal = originalTerminal;
    ConsoleHelper.resetStdin();
  }

  /** Puts {@code stdin} on standard input, with a terminal attached or not. */
  private void answering(String stdin, boolean terminal) {
    System.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
    ConsoleHelper.resetStdin();
    ConsoleHelper.terminal = () -> terminal;
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
    return fdeCommand().execute(args);
  }

  private FdeCommand.Box box() {
    return new FdeCommand.Box(
        tempDir.resolve("sail.db"),
        operator,
        handle,
        home(),
        hostKey(),
        workstationKey(),
        keygen,
        main);
  }

  private CommandLine fdeCommand() {
    var command = new CommandLine(new FdeCommand(this::box));
    command.setExecutionExceptionHandler(
        (failure, line, parsed) -> {
          refusal = failure.getMessage();
          return 1;
        });
    return command;
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

  /** The line a pairing writes: its login ends when its token does, to the second, in UTC. */
  private static String keyLine(SshPublicKey key, String handle, String expiresAt) {
    var deadline =
        expiresAt == null
            ? ""
            : ",expiry-time=\"" + SSHD_UTC.format(Instant.parse(expiresAt)) + "\"";
    return KEY_OPTIONS
        + deadline
        + " "
        + key.type()
        + " "
        + key.blob()
        + " sail-mast:"
        + handle
        + "\n";
  }

  /** The key blobs of the logins {@code handle}'s pairings hold in the operator's file. */
  private List<String> loginsOf(String handle) throws IOException {
    return Files.readAllLines(authorizedKeys()).stream()
        .map(line -> line.split(" "))
        .filter(fields -> fields[fields.length - 1].equals("sail-mast:" + handle))
        .map(fields -> fields[2])
        .toList();
  }

  private List<String> pairedKeys() {
    return inDb(
        db ->
            db.query(
                "SELECT public_key FROM fde_pairings",
                row -> SshPublicKey.parse(row.text(0)).blob()));
  }

  /** Standard output that holds the command printing {@code line} there until released. */
  private PrintStream pausingAt(String line, CountDownLatch reached, CountDownLatch released) {
    return new PrintStream(out, true, StandardCharsets.UTF_8) {
      @Override
      public void println(String printed) {
        if (printed.contains(line)) {
          reached.countDown();
          try {
            released.await();
          } catch (InterruptedException interrupted) {
            throw new IllegalStateException(interrupted);
          }
        }
        super.println(printed);
      }
    };
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
   * formats at the path {@code -f} names. An {@code incus} command goes to {@link #incus}.
   */
  private static final class Keygen implements ShellExec {

    private final List<List<String>> commands = new ArrayList<>();
    private final List<SshPublicKey> made = new ArrayList<>();
    private final Incus incus = new Incus();
    private Result failure;

    @Override
    public Result exec(List<String> command) throws IOException, InterruptedException {
      commands.add(List.copyOf(command));
      if (command.getFirst().equals("incus")) {
        return incus.exec(command);
      }
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
    public Result exec(List<String> command, Path workDir, Duration timeout)
        throws IOException, InterruptedException {
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
   * Incus as it answers for this box's containers, running or stopped alike: their names and
   * states, and the files {@code incus file pull} and {@code push} read and write in them.
   */
  private static final class Incus {

    private final Map<String, String> states = new LinkedHashMap<>();
    private final Map<String, String> types = new LinkedHashMap<>();
    private final Map<String, String> files = new LinkedHashMap<>();
    private final Map<String, ShellExec.Result> failures = new LinkedHashMap<>();
    private Runnable beforePull = () -> {};
    private boolean interrupted;

    void container(String name, String state, String file, String content) {
      states.put(name, state);
      types.put(name, "container");
      files.put(name + file, content);
    }

    /** A virtual machine, which serves no file while stopped. */
    void virtualMachine(String name, String state) {
      states.put(name, state);
      types.put(name, "virtual-machine");
      failures.put(
          name + PASSWD, new ShellExec.Result(1, "", "Error: VM agent isn't currently running\n"));
    }

    String file(String container, String file) {
      return files.get(container + file);
    }

    ShellExec.Result exec(List<String> command) throws IOException, InterruptedException {
      if (interrupted) {
        throw new InterruptedException();
      }
      var failure = failures.get(String.join(" ", command.subList(1, 3)));
      if (failure == null && command.get(2).equals("pull")) {
        failure = failures.get(command.get(3));
      }
      if (failure != null) {
        return failure;
      }
      return switch (command.get(1) + " " + command.get(2)) {
        case "list --format" ->
            new ShellExec.Result(
                0,
                states.entrySet().stream()
                    .map(
                        c ->
                            "{\"name\":\""
                                + c.getKey()
                                + "\",\"status\":\""
                                + c.getValue()
                                + "\",\"type\":\""
                                + types.get(c.getKey())
                                + "\"}")
                    .collect(Collectors.joining(",", "[", "]")),
                "");
        case "file pull" -> {
          beforePull.run();
          yield files.containsKey(command.get(3))
              ? new ShellExec.Result(0, files.get(command.get(3)), "")
              : new ShellExec.Result(1, "", "Error: Not Found\n");
        }
        case "file push" -> {
          files.put(command.getLast(), Files.readString(Path.of(command.get(command.size() - 2))));
          yield new ShellExec.Result(0, "", "");
        }
        default -> throw new IllegalArgumentException("Unexpected " + command);
      };
    }
  }

  /** The incus commands run, each staged file named {@code <staged>}. */
  private List<List<String>> incusCommands() {
    return keygen.commands.stream()
        .filter(command -> command.getFirst().equals("incus"))
        .map(
            command ->
                command.get(2).equals("push")
                    ? Stream.concat(
                            command.subList(0, command.size() - 2).stream(),
                            Stream.of("<staged>", command.getLast()))
                        .toList()
                    : command)
        .toList();
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
    assertEquals("rwx------", mode(home().resolve(".ssh")));
    assertEquals("rw-------", mode(authorizedKeys()));
    assertEquals(key.line() + "\n", Files.readString(workstationKey()));
    assertEquals("rw-r--r--", mode(workstationKey()));
    var token = tokens().getFirst();
    assertEquals(1, tokens().size());
    assertEquals("mast-ada", token.name());
    assertEquals("member", token.role());
    assertEquals("ada", token.fdeHandle());
    assertEquals(
        keyLine(key, "ada", token.expiresAt()),
        Files.readString(authorizedKeys()),
        "sshd refuses the code's key from the moment the box refuses its token");
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
    assertEquals(
        keyLine(second, "ada", tokens().getFirst().expiresAt()),
        Files.readString(authorizedKeys()));
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
                + " 'mast-ada' revoked. The workstation key was its key and is removed.",
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

  /** The sentence that refuses a code for {@code other}, an FDE this box is not. */
  private static String notThisBoxsOwn(String other, String host, String main) {
    return "A connect code logs in as root@"
        + host
        + ", which sees every project here. Pair "
        + other
        + " on its own box instead: set it up with 'sudo sail init --main "
        + main
        + "', then run 'sudo sail fde pair "
        + other
        + " --host <its address>' there.";
  }

  @Test
  void aConnectCodeIsMintedForNoFdeButThisBoxsOwn() throws Exception {
    handle = "uday";
    inDb(
        db -> {
          Acting.system(() -> new ProjectStore(db).upsert("acme", "name: acme\n"));
          var fdes = new FdeStore(db);
          fdes.add("client", null, "client@x.com", "member");
          fdes.restrict("client");
          fdes.grant("client", "acme", OperatorCommand.PLAIN);
          return fdes.add("eng", null, "eng@x.com", "member");
        });
    var roster = inDb(db -> new FdeStore(db).list());

    for (var refused :
        List.of(
            List.of("pair", "client", "--host", "m.acme.dev"),
            List.of("pair", "eng", "--host", "m.acme.dev", "--as-workstation-key"),
            List.of(
                "pair", "newbie", "--host", "m.acme.dev", "--role", "viewer", "--email", "n@x"))) {
      assertEquals(1, fde(refused.toArray(String[]::new)));

      assertEquals(notThisBoxsOwn(refused.get(1), "m.acme.dev", "m.acme.dev"), refusal);
    }
    assertEquals(roster, inDb(db -> new FdeStore(db).list()), "no FDE is added or changed");
    assertFalse(Files.exists(home().resolve(".ssh")));
    assertFalse(Files.exists(workstationKey()));
    assertEquals(List.of(), tokens());
    assertEquals(List.of(), pairedKeys());
    assertEquals(List.of(), keygen.commands);

    assertEquals(0, fde("pair", "uday", "--host", "m.acme.dev", "--email", "uday@x.com"), refusal);
    assertEquals("root", decoded(printedCode()).get("user"));
    assertEquals("uday", decoded(printedCode()).get("handle"));
  }

  @Test
  void aBoxThatNamesNoFdeOfItsOwnMintsNoCodeAndSaysHowToNameIt() {
    handle = null;

    assertEquals(1, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));

    assertEquals(
        "A connect code logs in as root@34.1.2.3, which sees every project here, so it is minted"
            + " only for this box's own FDE, and this box names none. If it is ada, name it with"
            + " 'sail host config set sync-handle ada' and pair again.",
        refusal);
    assertNothingWritten("ada");
  }

  private FdeStore.Fde replicated(String handle, String email) {
    return inDb(
        db -> {
          new FdeStore(db)
              .replicate(handle, "Client", email, "member", "active", "2026-10-01T00:00:00Z");
          return new FdeStore(db).byHandle(handle).orElseThrow();
        });
  }

  private long changes() {
    return inDb(db -> db.queryOne("SELECT count(*) FROM change_log", row -> row.integer(0)))
        .orElseThrow();
  }

  @Test
  void aNodePairsItsOwnFdeAsMainsRosterHasItAndWritesNoRosterRow() throws Exception {
    main = "sail@main";
    handle = "client";
    var client = replicated("client", "client@x.com");
    var changes = changes();

    assertEquals(0, fde("pair", "client", "--host", "c.acme.dev"), refusal);

    assertEquals(client, inDb(db -> new FdeStore(db).byHandle("client")).orElseThrow());
    assertEquals(changes, changes(), "the roster is main's");
    var key = keygen.made.getFirst();
    var token = tokens().getFirst();
    assertEquals(keyLine(key, "client", token.expiresAt()), Files.readString(authorizedKeys()));
    assertEquals(key.line() + "\n", Files.readString(workstationKey()));
    assertEquals(List.of(key.blob()), pairedKeys());
    assertEquals("mast-client", token.name());
    var fields = decoded(printedCode());
    assertEquals("client", handleOf((String) fields.get("token")), "the node's API answers it");
    assertEquals(
        List.of("client", "client@x.com", "c.acme.dev", "root"),
        List.of(fields.get("handle"), fields.get("email"), fields.get("host"), fields.get("user")));
    assertEquals(
        "  ✓ Paired client: Mast logs in as root@c.acme.dev. The code expires "
            + token.expiresAt()
            + ".",
        printed().getFirst());
  }

  @Test
  void aNodeRefusesToEditTheRosterOrPairAnyoneButItsOwnFde() {
    main = "sail@main";
    handle = "client";

    assertEquals(1, fde("pair", "client", "--host", "c.acme.dev"));
    assertEquals(
        "This node holds no roster row for 'client' yet, and main's roster names the FDEs here."
            + " Run 'sail sync', then pair again.",
        refusal);
    assertNothingWritten("client");

    var client = replicated("client", null);
    assertEquals(1, fde("pair", "client", "--host", "c.acme.dev"));
    assertEquals(
        "This box holds no email for 'client', and a connect code names one. Set it on main with"
            + " 'sudo sail fde update client --email <email>', run 'sail sync' here, then pair"
            + " again.",
        refusal);

    for (var option : List.of("--email=c@x.com", "--name=C", "--role=admin")) {
      assertEquals(1, fde("pair", "client", "--host", "c.acme.dev", option));
      assertEquals(
          FdeCommand.rosterEditOnNodeMessage("Changing an FDE's name, email or role"), refusal);
    }

    assertEquals(1, fde("pair", "uday", "--host", "c.acme.dev"));
    assertEquals(notThisBoxsOwn("uday", "c.acme.dev", "sail@main"), refusal);

    assertEquals(client, inDb(db -> new FdeStore(db).byHandle("client")).orElseThrow());
    assertFalse(Files.exists(home().resolve(".ssh")));
    assertFalse(Files.exists(workstationKey()));
    assertEquals(List.of(), tokens());
    assertEquals(List.of(), keygen.commands);
  }

  private static final String AUTHORIZED = "/.ssh/authorized_keys";

  /**
   * A catalog of three projects whose containers no longer match it: acme takes SSH logins as dev,
   * web was provisioned as eng and now names newuser, docs dropped its ssh section; plus a
   * container no project names. Each container trusts {@code key} beside the laptop's.
   */
  private void containersTrusting(SshPublicKey key) {
    inDb(
        db -> {
          Acting.system(
              () -> {
                var projects = new ProjectStore(db);
                for (var project : List.of("acme:dev", "web:newuser")) {
                  var nameAndUser = project.split(":");
                  projects.upsert(
                      nameAndUser[0],
                      "name: "
                          + nameAndUser[0]
                          + "\nssh:\n  user: "
                          + nameAndUser[1]
                          + "\n  authorized_keys:\n    - ${SSH_PUBLIC_KEY}\n");
                }
                projects.upsert("docs", "name: docs\n");
              });
          return null;
        });
    keygen.incus.container("acme", "Running", PASSWD, accounts("dev:x:1000:1000"));
    keygen.incus.files.put(
        "acme/home/dev" + AUTHORIZED,
        key.line()
            + "\n"
            + othersMentioning(key)
            + "no-pty,from=\"10.0.0.1\" "
            + key.line()
            + "\n");
    keygen.incus.container("web", "Stopped", PASSWD, accounts("eng:x:1001:1001"));
    keygen.incus.files.put("web/home/eng" + AUTHORIZED, LAPTOP.line() + "\n" + key.line() + "\n");
    keygen.incus.container("docs", "Running", PASSWD, accounts("dev:x:1000:1000"));
    keygen.incus.files.put("docs/home/dev" + AUTHORIZED, key.line() + "\n");
    keygen.incus.container("stray", "Stopped", PASSWD, accounts("dev:x:1000:1000"));
    keygen.incus.files.put("stray/home/dev" + AUTHORIZED, key.line() + "\n");
    keygen.incus.virtualMachine("win", "Stopped");
  }

  private static final String PASSWD = "/etc/passwd";

  /**
   * A container's accounts: the system ones, an ubuntu account never given a key, one whose home
   * climbs out of /home, and {@code user} at /home.
   */
  private static String accounts(String user) {
    var name = user.substring(0, user.indexOf(':'));
    return "root:x:0:0:root:/root:/bin/bash\n"
        + "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n"
        + "ubuntu:x:1002:1002::/home/ubuntu:/bin/bash\n"
        + "climber:x:1003:1003::/home/..:/bin/bash\n"
        + user
        + "::/home/"
        + name
        + ":/bin/bash\n";
  }

  /** Key lines that are not {@code key} though the blob of {@code key} appears in them. */
  private static String othersMentioning(SshPublicKey key) {
    return LAPTOP.line()
        + " retired "
        + key.blob()
        + "\n"
        + "command=\"echo "
        + key.type()
        + " "
        + key.blob()
        + "\" "
        + LAPTOP.line()
        + "\n"
        + "# "
        + key.line()
        + "\n";
  }

  /** What a revocation of {@code key} leaves in each container, and the incus it took. */
  private void assertContainersNoLongerTrust(SshPublicKey key) {
    assertEquals(othersMentioning(key), keygen.incus.file("acme", "/home/dev" + AUTHORIZED));
    assertEquals(LAPTOP.line() + "\n", keygen.incus.file("web", "/home/eng" + AUTHORIZED));
    assertEquals("", keygen.incus.file("docs", "/home/dev" + AUTHORIZED));
    assertEquals("", keygen.incus.file("stray", "/home/dev" + AUTHORIZED));
    var commands = new ArrayList<List<String>>();
    commands.add(List.of("incus", "list", "--format", "json"));
    for (var container :
        List.of("acme:dev:1000", "web:eng:1001", "docs:dev:1000", "stray:dev:1000")) {
      var nameUserId = container.split(":");
      var file = nameUserId[0] + "/home/" + nameUserId[1] + AUTHORIZED;
      commands.add(List.of("incus", "file", "pull", nameUserId[0] + PASSWD, "-"));
      commands.add(
          List.of("incus", "file", "pull", nameUserId[0] + "/home/ubuntu" + AUTHORIZED, "-"));
      commands.add(List.of("incus", "file", "pull", file, "-"));
      commands.add(
          List.of(
              "incus",
              "file",
              "push",
              "--uid",
              nameUserId[2],
              "--gid",
              nameUserId[2],
              "--mode",
              "0600",
              "<staged>",
              file));
    }
    assertEquals(commands, incusCommands());
  }

  @Test
  void unpairTakesItsKeyFromEveryContainerRunningOrStoppedAndLeavesTheirOtherKeys()
      throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var key = keygen.made.getFirst();
    containersTrusting(key);
    keygen.commands.clear();

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked. The workstation key was its key and is removed. Containers"
                + " of acme, web, docs, stray no longer trust it."),
        printed());
    assertContainersNoLongerTrust(key);
    assertFalse(Files.exists(workstationKey()));
    assertEquals("", Files.readString(authorizedKeys()));
    assertEquals(List.of(), tokens());
    assertEquals(List.of(), pairedKeys());
    keygen.commands.clear();

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(List.of("  ada has no pairing on this box; nothing to do."), printed());
    assertEquals(List.of(), keygen.commands);
  }

  @Test
  @Timeout(60)
  void aRevocationThatStartsWhileAnApplyInstallsTheKeyWaitsAndThenTakesItFromTheContainer()
      throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var key = keygen.made.getFirst();
    keygen.incus.container("acme", "Running", PASSWD, accounts("dev:x:1000:1000"));
    var unpair = new FutureTask<>(() -> fdeCommand().execute("unpair", "ada"));

    try (var applying = PairingTrust.hold(tempDir.resolve("sail.db"))) {
      var revoking = Thread.ofPlatform().start(unpair);
      FileMutexTest.awaitParked(revoking);
      assertFalse(unpair.isDone(), "the revocation waits for the apply that read the key");
      keygen.incus.files.put(
          "acme/home/dev" + AUTHORIZED, LAPTOP.line() + "\n" + key.line() + "\n");
    }

    assertEquals(0, unpair.get(), refusal);
    assertEquals(LAPTOP.line() + "\n", keygen.incus.file("acme", "/home/dev" + AUTHORIZED));
    assertFalse(Files.exists(workstationKey()));
  }

  @Test
  void removingAnFdeLeavesTheControlPlaneWritableWhileItReachesIntoContainers() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    containersTrusting(keygen.made.getFirst());
    var writable = new ArrayList<Boolean>();
    keygen.incus.beforePull = () -> writable.add(writeLockIsFree());

    assertEquals(0, fde("rm", "ada", "--force"), refusal);

    assertEquals(12, writable.size());
    assertEquals(List.of(true), writable.stream().distinct().toList());
  }

  /** Whether another connection takes the control plane's write lock without waiting. */
  private boolean writeLockIsFree() {
    try (var db = Sqlite.open(tempDir.resolve("sail.db"))) {
      db.execute("PRAGMA busy_timeout = 0");
      db.execute("BEGIN IMMEDIATE");
      db.execute("ROLLBACK");
      return true;
    } catch (RuntimeException busy) {
      return false;
    }
  }

  @Test
  void removingAnFdeTakesItsKeyFromEveryContainer() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var key = keygen.made.getFirst();
    containersTrusting(key);
    keygen.commands.clear();

    assertEquals(0, fde("rm", "ada", "--force"), refusal);

    assertEquals(
        "  ✓ Its Mast pairing is revoked: 1 key line removed from root's authorized_keys and"
            + " token 'mast-ada' revoked. The"
            + " workstation key was its key and is removed. Containers of acme, web, docs, stray no"
            + " longer trust it.",
        printed().getLast());
    assertContainersNoLongerTrust(key);
    assertFalse(Files.exists(workstationKey()));
    assertEquals("", Files.readString(authorizedKeys()));
  }

  @Test
  void pairingAgainTakesTheOldKeyFromEveryContainerAndTheNewOneIsTrustedAfterTheirNextApply()
      throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var old = keygen.made.getFirst();
    containersTrusting(old);
    keygen.commands.clear();

    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3"), refusal);

    var renewed = keygen.made.getLast();
    assertEquals(
        "  ✓ Unpaired ada first: 1 key line removed from root's authorized_keys and token"
            + " 'mast-ada' revoked. The workstation key was its key and is removed. Containers of"
            + " acme, web, docs, stray no longer trust it.",
        printed().getFirst());
    assertEquals(
        "  Containers of acme, docs, web trust this key only after their next 'sail project"
            + " apply'.",
        printed().getLast());
    assertEquals(renewed.line() + "\n", Files.readString(workstationKey()));
    assertEquals(List.of(renewed.blob()), loginsOf("ada"));
    assertEquals(othersMentioning(old), keygen.incus.file("acme", "/home/dev" + AUTHORIZED));
    assertEquals(LAPTOP.line() + "\n", keygen.incus.file("web", "/home/eng" + AUTHORIZED));
  }

  @Test
  void aWorkstationKeySetByHandOutlivesAPairingThatIsNotIt() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    HostConfigSetCommand.writeWorkstationKey(workstationKey(), LAPTOP.line());
    containersTrusting(LAPTOP);
    var acme = keygen.incus.file("acme", "/home/dev" + AUTHORIZED);
    var web = keygen.incus.file("web", "/home/eng" + AUTHORIZED);

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked."),
        printed());
    assertEquals(LAPTOP.line() + "\n", Files.readString(workstationKey()));
    assertEquals(acme, keygen.incus.file("acme", "/home/dev" + AUTHORIZED));
    assertEquals(web, keygen.incus.file("web", "/home/eng" + AUTHORIZED));
    assertTrue(
        incusCommands().stream().noneMatch(command -> command.get(2).equals("push")),
        "a file that does not hold the key is not written");
  }

  @Test
  void aContainerThatCannotBeEditedKeepsTheRecordSoTheNextRunFinishesTheRevocation()
      throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var key = keygen.made.getFirst();
    containersTrusting(key);
    keygen.incus.failures.put("list --format", new ShellExec.Result(1, "", "Error: unreachable\n"));

    assertEquals(1, fde("unpair", "ada"));

    assertEquals(
        "Could not remove ada's key from this box's containers (incus list failed: Error:"
            + " unreachable\n). Its login is revoked, and its token and record are kept until the"
            + " key is gone: check that 'incus list' answers, then run the command again.",
        refusal);
    assertEquals("", Files.readString(authorizedKeys()), "the login goes first");
    assertEquals(List.of(key.blob()), pairedKeys());
    assertEquals(List.of("mast-ada"), tokens().stream().map(TokenStore.TokenInfo::name).toList());
    assertEquals(key.line() + "\n", Files.readString(workstationKey()));

    keygen.incus.failures.clear();
    keygen.incus.failures.put("file pull", new ShellExec.Result(1, "", "Error: I/O error\n"));
    assertEquals(1, fde("unpair", "ada"));
    assertEquals(
        "Could not remove ada's key from this box's containers (could not read"
            + " /etc/passwd in acme: Error: I/O error). Its login is revoked,"
            + " and its token and record are kept until the key is gone: check that 'incus list'"
            + " answers, then run the command again.",
        refusal);

    keygen.incus.failures.clear();
    keygen.incus.failures.put(
        "acme/home/dev" + AUTHORIZED, new ShellExec.Result(1, "", "Error: I/O error\n"));
    assertEquals(1, fde("unpair", "ada"));
    assertEquals(
        "Could not remove ada's key from this box's containers (could not read"
            + " /home/dev/.ssh/authorized_keys in acme: Error: I/O error). Its login is revoked,"
            + " and its token and record are kept until the key is gone: check that 'incus list'"
            + " answers, then run the command again.",
        refusal);
    assertEquals(List.of(key.blob()), pairedKeys());

    keygen.incus.failures.clear();
    keygen.incus.files.remove("web/home/eng" + AUTHORIZED);
    keygen.commands.clear();
    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 0 key lines removed from root's authorized_keys and token"
                + " 'mast-ada' revoked. The workstation key was its key and is removed. Containers"
                + " of acme, docs, stray no longer trust it."),
        printed(),
        "a container whose user has no authorized_keys trusts nothing to remove");
    assertEquals(List.of(), pairedKeys());
    assertFalse(Files.exists(workstationKey()));
  }

  @Test
  void aWorkstationKeyThatCannotBeRemovedKeepsTheRecordSoTheNextRunFinishesIt() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var sail = workstationKey().getParent();
    Files.setPosixFilePermissions(sail, PosixFilePermissions.fromString("r-x------"));
    try {
      assertEquals(1, fde("unpair", "ada"));
    } finally {
      Files.setPosixFilePermissions(sail, PosixFilePermissions.fromString("rwx------"));
    }

    assertEquals(
        "Could not remove "
            + workstationKey()
            + ", which may hold ada's key (AccessDeniedException). Its login is revoked, and its"
            + " token and record are kept until the key is gone: check that "
            + workstationKey()
            + " and its directory are readable and writable by root, then run the command again.",
        refusal);
    assertEquals(List.of(keygen.made.getFirst().blob()), pairedKeys());

    assertEquals(0, fde("unpair", "ada"), refusal);
    assertFalse(Files.exists(workstationKey()));
    assertEquals(List.of(), pairedKeys());
  }

  @Test
  void aWorkstationKeyFileAlreadyGoneLeavesTheRestOfTheRevocationToRun() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    Files.delete(workstationKey());
    out.reset();

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked."),
        printed());
    assertEquals(List.of(), pairedKeys());
  }

  @Test
  void anInterruptedRevocationKeepsTheRecordAndTheInterrupt() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    keygen.incus.interrupted = true;

    try {
      assertEquals(1, fde("unpair", "ada"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }

    assertEquals("Interrupted while removing ada's key from this box's containers.", refusal);
    assertEquals(List.of(keygen.made.getFirst().blob()), pairedKeys());
    assertEquals(List.of("mast-ada"), tokens().stream().map(TokenStore.TokenInfo::name).toList());
  }

  @Test
  void aWorkstationKeyThatCannotBeReadKeepsTheRecordSoTheNextRunFinishesIt() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    Files.setPosixFilePermissions(workstationKey(), PosixFilePermissions.fromString("---------"));
    try {
      assertEquals(1, fde("unpair", "ada"));
    } finally {
      Files.setPosixFilePermissions(workstationKey(), PosixFilePermissions.fromString("rw-------"));
    }

    assertEquals(
        "Could not remove "
            + workstationKey()
            + ", which may hold ada's key (AccessDeniedException). Its login is revoked, and its"
            + " token and record are kept until the key is gone: check that "
            + workstationKey()
            + " and its directory are readable and writable by root, then run the command again.",
        refusal);
    assertEquals(List.of(keygen.made.getFirst().blob()), pairedKeys());
    assertEquals(List.of("mast-ada"), tokens().stream().map(TokenStore.TokenInfo::name).toList());

    assertEquals(0, fde("unpair", "ada"), refusal);
    assertFalse(Files.exists(workstationKey()));
    assertEquals(List.of(), pairedKeys());
  }

  @Test
  void aWorkstationKeyFileThatHoldsNoKeyIsLeftAlone() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    Files.writeString(workstationKey(), "not a key\n");

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked."),
        printed());
    assertEquals("not a key\n", Files.readString(workstationKey()));
  }

  /** A box that paired {@code client} before codes were its own FDE's, and now pairs uday. */
  private String[] pairedClientThenUday() throws Exception {
    handle = "client";
    assertEquals(0, fde("pair", "client", "--host", "m", "--email", "client@x.com"));
    var client = (String) decoded(printedCode()).get("token");
    handle = "uday";
    assertEquals(
        0, fde("pair", "uday", "--host", "m", "--email", "uday@x.com", "--as-workstation-key"));
    return new String[] {client, (String) decoded(printedCode()).get("token")};
  }

  @Test
  void anUpgradeRevokesEveryPairingButTheBoxsOwnOnceAndSaysWhichThroughTheSameSeam()
      throws Exception {
    var tokens = pairedClientThenUday();
    var uday = loginsOf("uday");
    var workstation = Files.readString(workstationKey());
    out.reset();

    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });

    assertEquals(
        List.of(
            "  ✓ Revoked client's connect code: a code logs in as root and is minted only for this"
                + " box's own FDE."),
        printed());
    assertEquals(List.of(), loginsOf("client"));
    assertNull(handleOf(tokens[0]));
    assertEquals(List.of("uday"), inDb(db -> new FdePairingStore(db).handles()));
    assertEquals(uday, loginsOf("uday"));
    assertEquals("uday", handleOf(tokens[1]));
    assertEquals(workstation, Files.readString(workstationKey()), "it is uday's key");

    out.reset();
    handle = "nobody";
    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });

    assertEquals(List.of(), printed(), "it runs once");
    assertEquals("uday", handleOf(tokens[1]));
  }

  @Test
  void anUpgradeOfABoxThatNamesNoFdeRevokesEveryPairingAndSaysHowItsOperatorPairsAgain()
      throws Exception {
    handle = "uday";
    assertEquals(0, fde("pair", "uday", "--host", "m", "--email", "uday@x.com"));
    var token = (String) decoded(printedCode()).get("token");
    handle = null;
    out.reset();

    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });

    assertEquals(
        List.of(
            "  ✓ Revoked uday's connect code: a code logs in as root and is minted only for this"
                + " box's own FDE. This box names none: if it is uday, name it with 'sail"
                + " host config set sync-handle uday' and pair again."),
        printed());
    assertEquals(List.of(), loginsOf("uday"));
    assertNull(handleOf(token));
  }

  @Test
  void aRehearsalOnACopyRevokesNoPairingAndTheBoxsOwnMigrateDoes() throws Exception {
    var tokens = pairedClientThenUday();
    var convergence =
        new MigrateCommand.Convergence(
            (db, json) -> {},
            (db, json) ->
                MigrateCommand.revokeOtherFdesPairings(db, this::box, json, OperatorCommand.PLAIN));
    assertFalse(
        DataMigrations.all(OperatorCommand.PLAIN).stream()
            .anyMatch(migration -> migration.name().equals(OwnBoxPairings.NAME)),
        "every lane runs the registry; only the box's own convergence revokes");

    MigrateCommand.runMigrations(
        tempDir.resolve("sail.db"),
        true,
        true,
        new MigrateCommand.Scope.DatabaseOnly("only the copy"),
        convergence,
        OperatorCommand.PLAIN);

    assertEquals("client", handleOf(tokens[0]));
    assertEquals(1, loginsOf("client").size());

    MigrateCommand.runMigrations(
        tempDir.resolve("sail.db"),
        true,
        true,
        new MigrateCommand.Scope.Box(),
        convergence,
        OperatorCommand.PLAIN);

    assertNull(handleOf(tokens[0]));
    assertEquals(List.of(), loginsOf("client"));
  }

  @Test
  void anUpgradeThatCannotRevokeSaysSoAndTheNextMigrateFinishesIt() throws Exception {
    var tokens = pairedClientThenUday();
    var ssh = home().resolve(".ssh");
    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("r-x------"));
    err.reset();
    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });
    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));

    assertEquals(
        "  ⚠ Connect codes of FDEs other than this box's own are not all revoked: Could not remove"
            + " client's key line from root's authorized_keys (AccessDeniedException), so nothing"
            + " of client was removed and its pairing still works. Check that "
            + ssh
            + " is writable by root, then run the command again. Run 'sail migrate' again.\n",
        err.toString(StandardCharsets.UTF_8));
    assertEquals("client", handleOf(tokens[0]));

    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, true, OperatorCommand.PLAIN);
          return null;
        });

    assertNull(handleOf(tokens[0]));
  }

  @Test
  void anUpgradeThatCannotTakeThePairingLockRevokesNothingAndSaysSo() throws Exception {
    var tokens = pairedClientThenUday();
    var lock = tempDir.resolve("sail.db.pairings.lock");
    Files.delete(lock);
    Files.createDirectory(lock);
    err.reset();

    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });

    assertEquals(
        "  ⚠ Connect codes of FDEs other than this box's own are not all revoked: Could not take"
            + " the pairing lock ("
            + lock
            + ": Is a directory). Run 'sail migrate' again.\n",
        err.toString(StandardCharsets.UTF_8));
    assertEquals("client", handleOf(tokens[0]));
  }

  @Test
  void anUpgradeThatRevokesOnePairingAndFailsOnTheNextSaysTheOneItRevoked() throws Exception {
    var tokens = pairedClientThenUday();
    handle = "ada";
    assertEquals(
        0, fde("pair", "ada", "--host", "m", "--email", "ada@x.com", "--as-workstation-key"));
    var ada = (String) decoded(printedCode()).get("token");
    handle = "uday";
    var client =
        inDb(
            db ->
                new FdePairingStore(db)
                    .find(new FdeStore(db).byHandle("client").orElseThrow().id())
                    .orElseThrow()
                    .publicKey());
    keygen.incus.container("acme", "Running", PASSWD, accounts("dev:x:1000:1000"));
    keygen.incus.files.put("acme/home/dev" + AUTHORIZED, client + "\n");
    keygen.incus.failures.put("file push", new ShellExec.Result(1, "", "Error: I/O error\n"));
    out.reset();
    err.reset();

    inDb(
        db -> {
          MigrateCommand.revokeOtherFdesPairings(db, this::box, false, OperatorCommand.PLAIN);
          return null;
        });

    assertEquals(
        List.of(
            "  ✓ Revoked ada's connect code: a code logs in as root and is minted only for this"
                + " box's own FDE."),
        printed());
    assertEquals(
        "  ⚠ Connect codes of FDEs other than this box's own are not all revoked: Could not remove"
            + " client's key from this box's containers (Failed to push file to"
            + " /home/dev/.ssh/authorized_keys: Error: I/O error\n). Its login is revoked, and its"
            + " token and record are kept until the key is gone: check that 'incus list' answers,"
            + " then run the command again. Run 'sail migrate' again.\n",
        err.toString(StandardCharsets.UTF_8));
    assertNull(handleOf(ada));
    assertEquals("client", handleOf(tokens[0]));
    assertEquals(List.of("client", "uday"), inDb(db -> new FdePairingStore(db).handles()));
  }

  @ParameterizedTest
  @CsvSource({"me@mac, ' (me@mac)'", ",''"})
  void pairingWhereTheWorkstationKeyIsAnothersIsRefusedNamingItAndWritesNothing(
      String comment, String named) throws Exception {
    var registered = SshPublicKey.parse(TestSshKeys.ed25519("laptop", comment));
    Files.createDirectories(workstationKey().getParent());
    Files.writeString(workstationKey(), registered.line() + "\n");

    var exit = fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com");

    assertEquals(1, exit);
    assertEquals(
        "This box's containers trust the workstation key "
            + registered.fingerprint()
            + named
            + ", and they trust one key. Pass --as-workstation-key to replace it with ada's.",
        refusal);
    assertEquals(registered.line() + "\n", Files.readString(workstationKey()));
    Files.delete(workstationKey());
    assertNothingWritten("ada");
  }

  @Test
  void asWorkstationKeyReplacesAnothersKeyAndNamesItsOwner() throws Exception {
    Files.createDirectories(workstationKey().getParent());
    Files.writeString(workstationKey(), LAPTOP.line() + "\n");

    var exit =
        fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com", "--as-workstation-key");

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

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys and token"
                + " 'mast-ada' revoked. The workstation key was its key and is removed."),
        printed());
    assertEquals(LAPTOP.line() + "\n", Files.readString(authorizedKeys()));
    assertNull(handleOf(token));
    assertEquals(List.of(), tokens());
    var ada = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    assertTrue(inDb(db -> new FdePairingStore(db).find(ada.id())).isEmpty());
    assertFalse(Files.exists(workstationKey()), "containers stop trusting the pairing's key");

    assertEquals(0, fde("unpair", "ada"), refusal);

    assertEquals(List.of("  ada has no pairing on this box; nothing to do."), printed());
    assertEquals(LAPTOP.line() + "\n", Files.readString(authorizedKeys()));
  }

  @Test
  void unpairSaysOnlyWhatItRemoved() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    inDb(db -> new TokenStore(db).revoke("mast-ada"));

    assertEquals(0, fde("unpair", "ada"), refusal);
    assertEquals(
        List.of(
            "  ✓ Unpaired ada: 1 key line removed from root's authorized_keys. The workstation key"
                + " was its key and is removed."),
        printed());

    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3"));
    var ada = inDb(db -> new FdeStore(db).byHandle("ada")).orElseThrow();
    inDb(db -> new FdePairingStore(db).remove(ada.id()));

    assertEquals(0, fde("unpair", "ada"), refusal);
    assertEquals(
        List.of("  ✓ Unpaired ada: 1 key line removed from root's authorized_keys."), printed());
    assertEquals("", Files.readString(authorizedKeys()));
    assertEquals(
        List.of("mast-ada"),
        tokens().stream().map(TokenStore.TokenInfo::name).toList(),
        "a token no pairing names is not this command's to revoke");
    assertTrue(
        Files.exists(workstationKey()), "without the record, nothing names the key it must match");

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
            "  ✓ Its Mast pairing is revoked: 1 key line removed from root's authorized_keys"
                + " and token 'mast-ada' revoked."
                + " The workstation key was its key and is removed."),
        printed());
    assertEquals("", Files.readString(authorizedKeys()));
    assertFalse(Files.exists(workstationKey()));
    assertNull(handleOf(token));
    assertTrue(inDb(db -> new FdeStore(db).byHandle("ada")).isEmpty());
    assertEquals(
        0L,
        inDb(db -> db.queryOne("SELECT count(*) FROM fde_pairings", row -> row.integer(0)))
            .orElseThrow());
  }

  @Test
  void aRemovalWithNoTerminalToConfirmOnIsRefusedAndKeepsTheFdeAndItsKeys() {
    assertEquals(0, fde("add", "bob", "--key", LAPTOP.line()), refusal);
    answering("", false);

    assertEquals(1, fde("rm", "bob"));

    assertEquals("Not removed: no terminal to confirm on. Re-run with --force.", refusal);
    assertEquals(List.of(), printed());
    var bob = inDb(db -> new FdeStore(db).byHandle("bob")).orElseThrow();
    assertEquals(1, inDb(db -> new FdeSshKeyStore(db).listForFde(bob.id())).size());
  }

  @Test
  void aRemovalAnsweredWithAnEmptyLineOnATerminalIsCancelled() {
    inDb(db -> new FdeStore(db).add("bob", null, null, "member"));
    answering("\n", true);

    assertEquals(0, fde("rm", "bob"), refusal);

    assertEquals(
        List.of("  Remove FDE 'bob' and revoke all its credentials? [y/N]   Cancelled."),
        printed());
    assertTrue(inDb(db -> new FdeStore(db).byHandle("bob")).isPresent());
  }

  @Test
  void aRemovalAnsweredYesOnATerminalRemoves() {
    inDb(db -> new FdeStore(db).add("bob", null, null, "member"));
    answering("y\n", true);

    assertEquals(0, fde("rm", "bob"), refusal);

    assertTrue(inDb(db -> new FdeStore(db).byHandle("bob")).isEmpty());
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

  @ParameterizedTest
  @ValueSource(strings = {"r-x------", "rw-------"})
  void aRemovalThatCannotRevokeTheLoginRemovesNothingAndTheNextRunFinishesIt(String mode)
      throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var token = (String) decoded(printedCode()).get("token");
    var line = Files.readString(authorizedKeys());
    var ssh = home().resolve(".ssh");
    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString(mode));
    var refused =
        "Could not remove ada's key line from root's authorized_keys (AccessDeniedException), so"
            + " nothing of ada was removed and its pairing still works. Check that "
            + ssh
            + " is writable by root, then run the command again.";

    for (var command : List.of(List.of("rm", "ada", "--force"), List.of("unpair", "ada"))) {
      assertEquals(1, fde(command.toArray(String[]::new)));

      assertEquals(refused, refusal);
      assertEquals(List.of(), printed());
      assertEquals(List.of(keygen.made.getFirst().blob()), pairedKeys());
      assertEquals(keygen.made.getFirst().line() + "\n", Files.readString(workstationKey()));
    }

    Files.setPosixFilePermissions(ssh, PosixFilePermissions.fromString("rwx------"));
    assertEquals(line, Files.readString(authorizedKeys()));
    assertEquals("ada", handleOf(token));

    assertEquals(0, fde("rm", "ada", "--force"), refusal);

    assertEquals(
        List.of(
            "  ✓ FDE removed: ada",
            "  Owned tokens, SSH keys, sessions, passkeys, and enrollment tickets are revoked.",
            "  ✓ Its Mast pairing is revoked: 1 key line removed from root's authorized_keys"
                + " and token 'mast-ada' revoked."
                + " The workstation key was its key and is removed."),
        printed());
    assertEquals("", Files.readString(authorizedKeys()));
    assertNull(handleOf(token));
    assertTrue(inDb(db -> new FdeStore(db).byHandle("ada")).isEmpty());
  }

  @ParameterizedTest
  @Timeout(60)
  @CsvSource({"pair ada --host 34.1.2.3, 1", "unpair ada, 0", "rm ada --force, 0"})
  void aCommandThatStartsWhileAPairingIsHalfMadeWaitsForItAndTheLoginsStayTheRecords(
      String later, int pairings) throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var halfMade = new CountDownLatch(1);
    var finish = new CountDownLatch(1);
    System.setOut(pausingAt("Unpaired ada first", halfMade, finish));
    var first = new FutureTask<>(() -> fdeCommand().execute("pair", "ada", "--host", "34.1.2.3"));
    var second = new FutureTask<>(() -> fdeCommand().execute(later.split(" ")));

    Thread.ofPlatform().start(first);
    halfMade.await();
    var waiting = Thread.ofPlatform().start(second);
    while (waiting.isAlive() && waiting.getState() != Thread.State.WAITING) {
      Thread.onSpinWait();
    }
    finish.countDown();

    assertEquals(0, first.get(), refusal);
    assertEquals(0, second.get(), refusal);
    assertEquals(pairedKeys(), loginsOf("ada"), "every login is one a record can revoke");
    assertEquals(pairings, pairedKeys().size());
    assertEquals(pairings, tokens().size());
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
  void aNodeRefusesToRemoveWithTheRosterSentence() {
    main = "sail@main";

    assertEquals(1, fde("rm", "ada", "--force"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Removing an FDE"), refusal);
  }

  @Test
  void anFdeAddedScopedSeesNothingUntilGrantedAndGrantsAreIdempotent() {
    inDb(
        db -> {
          Acting.system(
              () -> {
                new ProjectStore(db).upsert("alpha", "name: alpha\n");
                new ProjectStore(db).upsert("beta", "name: beta\n");
              });
          return null;
        });

    assertEquals(0, fde("add", "nia", "--scoped"));
    assertEquals(
        List.of("  ✓ FDE added: nia (member, scoped: grant projects with 'sail fde grant')"),
        printed());
    assertEquals(0, fde("add", "uma"));
    assertEquals(List.of("  ✓ FDE added: uma (member)"), printed());
    assertEquals(ProjectScope.none(), inDb(db -> new FdeStore(db).scopeOf("nia")));
    assertEquals(ProjectScope.all(), inDb(db -> new FdeStore(db).scopeOf("uma")));

    inDb(
        db -> {
          db.execute("UPDATE change_heads SET project = NULL");
          return null;
        });
    assertEquals(0, fde("grant", "nia", "alpha", "--dry-run"));
    assertEquals(
        List.of("  ✓ Would grant alpha to nia. Its box holds the project after its next sync."),
        printed());
    assertEquals(ProjectScope.none(), inDb(db -> new FdeStore(db).scopeOf("nia")));
    assertEquals(List.of(), inDb(db -> new ChangeLog(db).heldProjects()));

    assertEquals(0, fde("grant", "nia", "alpha"));
    assertEquals(
        List.of("  ✓ Granted alpha to nia. Its box holds the project after its next sync."),
        printed());
    assertEquals(
        List.of("alpha", "beta"),
        inDb(db -> new ChangeLog(db).heldProjects()),
        "a grant fills the heads a release before this one wrote without their project");
    assertEquals(0, fde("grant", "nia", "alpha"));
    assertEquals(List.of("  nia already holds alpha; nothing to do."), printed());
    assertEquals(0, fde("grant", "nia", "beta", "--json"));
    assertEquals(
        List.of(
            "{\"handle\": \"nia\", \"project\": \"beta\", \"granted\": true, \"dry_run\":"
                + " false, \"projects\": [\"alpha\", \"beta\"]}"),
        printed());

    assertEquals(0, fde("list"));
    var table = String.join("\n", printed());
    assertTrue(table.contains("SCOPE"), table);
    assertTrue(table.contains("2: alpha, beta"), table);
    assertTrue(table.lines().anyMatch(line -> line.contains("uma") && line.contains("all")), table);

    assertEquals(0, fde("revoke", "nia", "alpha", "--dry-run", "--json"));
    assertEquals(
        List.of(
            "{\"handle\": \"nia\", \"project\": \"alpha\", \"revoked\": true, \"dry_run\":"
                + " true, \"projects\": [\"alpha\", \"beta\"]}"),
        printed());
    assertEquals(0, fde("revoke", "nia", "alpha"));
    assertEquals(
        List.of(
            "  ✓ Revoked alpha from nia. Its box drops the project, unpushed work included, on its"
                + " next sync."),
        printed());
    assertEquals(0, fde("revoke", "nia", "alpha"));
    assertEquals(List.of("  nia does not hold alpha; nothing to do."), printed());
    assertEquals(0, fde("revoke", "nia", "beta", "--dry-run"));
    assertEquals(
        List.of(
            "  ✓ Would revoke beta from nia. Its box drops the project, unpushed work included,"
                + " on its next sync."),
        printed());
    assertEquals(ProjectScope.of(List.of("beta")), inDb(db -> new FdeStore(db).scopeOf("nia")));
  }

  @Test
  void aGrantNamesAScopedFdeAndAProjectInTheCatalog() {
    inDb(
        db -> {
          Acting.system(() -> new ProjectStore(db).upsert("alpha", "name: alpha\n"));
          return null;
        });
    assertEquals(0, fde("add", "nia", "--scoped"));
    assertEquals(0, fde("add", "uma"));

    assertEquals(1, fde("grant", "nia", "ghost"));
    assertEquals("Unknown project 'ghost'. List the catalog with 'sail project list'.", refusal);
    assertEquals(1, fde("grant", "nobody", "alpha"));
    assertEquals("Unknown FDE 'nobody'.", refusal);
    assertEquals(1, fde("grant", "uma", "alpha"));
    assertEquals(
        "FDE 'uma' sees every project, so there is nothing to grant or revoke. Only an FDE added"
            + " with 'sail fde add <handle> --scoped' is granted projects.",
        refusal);
    assertEquals(1, fde("add", "root", "--role", "admin", "--scoped"));
    assertEquals(
        "FDE 'root' cannot be both scoped and an admin: an admin edits the roster, and so could"
            + " grant itself any project. Scope a member or a viewer.",
        refusal);
    handle = "uma";
    var own =
        "FDE 'uma' is this box's own and acts as an admin here, so it cannot be scoped. Scope a"
            + " member or a viewer.";
    assertEquals(1, fde("grant", "uma", "alpha"));
    assertEquals(own, refusal);
    assertEquals(1, fde("add", "uma", "--scoped"), "an FDE already added is told the same");
    assertEquals(own, refusal);
    assertEquals(0, fde("grant", "nia", "alpha", "--dry-run"), "another FDE is granted as before");
    handle = null;
    assertTrue(
        inDb(db -> new FdeStore(db).byHandle("root")).isEmpty(), "the refused add left no FDE");
    assertEquals(1, fde("grant", "nia", "Not/A/Name"));
    assertEquals(
        "Invalid project name: 'Not/A/Name'. Must match [a-z0-9][a-z0-9-]*, max 63 characters.",
        refusal);
    assertEquals(1, fde("revoke", "Bad Handle", "alpha"));
    assertEquals(0, fde("revoke", "nia", "ghost"), "a project since pruned is still revocable");
    assertEquals(ProjectScope.none(), inDb(db -> new FdeStore(db).scopeOf("nia")));
  }

  @Test
  void aNodeRefusesToAddGrantOrRevokeWithTheRosterSentence() {
    main = "sail@main";

    assertEquals(1, fde("add", "nia", "--scoped"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Adding an FDE"), refusal);
    assertEquals(1, fde("grant", "nia", "alpha"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Granting a project"), refusal);
    assertEquals(1, fde("revoke", "nia", "alpha"));
    assertEquals(FdeCommand.rosterEditOnNodeMessage("Revoking a project"), refusal);
    assertTrue(inDb(db -> new FdeStore(db).list()).isEmpty());
  }

  @Test
  void aBoxWithNoFdeListsNone() {
    assertEquals(0, fde("list"));
    assertEquals(List.of("  No FDEs. Add one with 'sail fde add <handle>'."), printed());
  }

  @Test
  void theSshGatewaysAccountNeitherMakesNorRevokesAPairing() throws Exception {
    assertEquals(0, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));
    var token = (String) decoded(printedCode()).get("token");
    var line = Files.readString(authorizedKeys());
    operator = "sail";
    keygen.commands.clear();

    assertEquals(1, fde("pair", "ada", "--host", "34.1.2.3"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sudo sail fde pair ada' on the host, as the account sail-api runs as.",
        refusal);
    assertEquals(1, fde("unpair", "ada"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sudo sail fde unpair ada' on the host, as the account sail-api runs as.",
        refusal);
    assertEquals(1, fde("rm", "ada", "--force"));
    assertEquals(
        "A pairing is a login of this box's operator, and an SSH-key session runs as the gateway's"
            + " account. Run 'sudo sail fde rm ada' on the host, as the account sail-api runs as.",
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
  void aNodeThatPinnedThisBoxsHostKeyIsAddedAsToday() {
    assertEquals(0, fde("add", "nia", "--main-host-key", HOST_KEY.fingerprint()));

    assertEquals("member", inDb(db -> new FdeStore(db).byHandle("nia")).orElseThrow().role());
  }

  @Test
  void aNodeThatPinnedAnotherKeyIsRefusedBeforeAnythingIsWritten() {
    var other = SshPublicKey.parse(TestSshKeys.ed25519("other", "root@elsewhere")).fingerprint();

    assertEquals(1, fde("add", "nia", "--key", LAPTOP.line(), "--main-host-key", other));

    assertEquals(
        "The node pinned "
            + other
            + " for this box, but this box's host key is "
            + HOST_KEY.fingerprint()
            + ": something between that node and this box answered for it. Do not add this key;"
            + " ask the engineer to re-run 'sudo sail init --main <this box>' on a network path"
            + " you trust.",
        refusal);
    assertNothingWritten("nia");
    assertEquals(List.of(), inDb(db -> new FdeSshKeyStore(db).list()));
  }

  @Test
  void aMalformedPinIsRefusedAtTheEdge() {
    assertEquals(1, fde("add", "nia", "--main-host-key", "SHA256:short"));

    assertEquals(
        "--main-host-key takes the SHA256 fingerprint the node's join printed (SHA256: and 43"
            + " base64 characters), not 'SHA256:short'.",
        refusal);
    assertNothingWritten("nia");
  }

  @Test
  void aPinOnABoxWithNoHostKeyIsRefusedNamingTheFile() throws Exception {
    Files.delete(hostKey());

    assertEquals(1, fde("add", "nia", "--main-host-key", HOST_KEY.fingerprint()));

    assertEquals(
        "This box has no SSH host key at "
            + hostKey()
            + ". Generate one with 'sudo ssh-keygen -A', then run this again.",
        refusal);
    assertNothingWritten("nia");
  }

  @Test
  void aBoxWithNoHostKeyIsRefusedNamingTheFileAndWritesNothing() throws Exception {
    Files.delete(hostKey());

    assertEquals(1, fde("pair", "ada", "--host", "34.1.2.3", "--email", "ada@x.com"));

    assertEquals(
        "This box has no SSH host key at "
            + hostKey()
            + ". Generate one with 'sudo ssh-keygen -A', then run this again.",
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
  void anFdeThisBoxHoldsKeepsItsRoleAndEmailUnlessTheOptionsSayOtherwise() throws Exception {
    inDb(db -> new FdeStore(db).add("ada", "Ada", "ada@x.com", "admin"));

    assertEquals(0, fde("pair", "ada", "--host", "box", "--no-expiry"), refusal);

    assertEquals(
        keyLine(keygen.made.getFirst(), "ada", null),
        Files.readString(authorizedKeys()),
        "a code that never expires carries a login that never does");

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
    assertEquals(
        keyLine(keygen.made.getFirst(), "ada", tokens().getFirst().expiresAt()),
        Files.readString(authorizedKeys()));
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
  void everyCommandAnFdeVerbNamesIsWordedForTheOperator() {
    var sudo = new OperatorCommand(true);
    var ada = new FdeStore.Fde("7", "ada", "Ada", "ada@example.com", "member", "active", "2026");
    String unknownProject;
    try (var db = Sqlite.openMemory()) {
      new SchemaManager(db).migrate();
      unknownProject =
          assertThrows(
                  IllegalArgumentException.class,
                  () -> FdeCommand.scopeEdit(db, "ada", "ghost", true, false, false, sudo))
              .getMessage();
    }

    assertEquals(
        List.of(
            "FDE 'ada' already exists. Change it with 'sudo sail fde update ada'.",
            "  ✓ FDE added: ada (member, scoped: grant projects with 'sudo sail fde grant')",
            "Unknown project 'ghost'. List the catalog with 'sudo sail project list'.",
            "  Containers of acme, beta trust this key only after their next 'sudo sail project"
                + " apply'.",
            "FDE 'ada' is disabled on this box, so a code for it would open nothing. Remove it with"
                + " 'sudo sail fde rm ada' and pair again.",
            "  No FDEs. Add one with 'sudo sail fde add <handle>'.",
            "Unknown FDE 'ada'. Add it with 'sudo sail fde add ada'.",
            "  No SSH keys. Register one with 'sudo sail fde key add'.",
            "'ada' is neither a known FDE handle nor a SHA256: fingerprint. See 'sudo sail fde key"
                + " list'.",
            "    sudo sail fde key rm SHA256:abc",
            "'ada' has no passkeys. Enroll one with 'sudo sail fde enroll ada'.",
            "  No passkeys for 'ada'. Enroll one with 'sudo sail fde enroll ada'.",
            "  'ada' has no passkeys left. Re-enroll with 'sudo sail fde enroll ada'."),
        List.of(
            FdeCommand.alreadyExists("ada", sudo),
            Ansi.OFF.string(FdeCommand.added(ada, true, sudo)),
            unknownProject,
            FdeCommand.trustedAfterApply("acme, beta", sudo),
            FdeCommand.disabledForPairing("ada", sudo),
            FdeCommand.noFdes(sudo),
            FdeCommand.unknownFde("ada", sudo),
            FdeCommand.noKeys(sudo),
            FdeCommand.neitherHandleNorFingerprint("ada", sudo),
            FdeCommand.keyRemoval("SHA256:abc", sudo),
            FdeCommand.Passkey.noMatchMessage("ada", "zz", List.of(), sudo),
            FdeCommand.noPasskeys("ada", sudo),
            Ansi.OFF.string(FdeCommand.noPasskeysLeft("ada", sudo))));
  }

  @Test
  void updateChangesOnlyWhatIsPassedAndRefusesAnEmptyOrUnknownEdit() {
    assertEquals(0, fde("add", "ada", "--name", "Ada", "--email", "ada@example.com"));

    assertEquals(0, fde("update", "ada", "--role", "admin"));

    assertEquals(List.of("  ✓ Updated ada (admin)"), printed());
    var updated = inDb(db -> new FdeStore(db).byHandle("ada").orElseThrow());
    assertEquals("admin", updated.role());
    assertEquals("Ada", updated.displayName());
    assertEquals("ada@example.com", updated.email());
    assertEquals(1, fde("update", "ada"));
    assertEquals("Nothing to update. Pass --name, --email, and/or --role.", refusal);
    assertEquals(1, fde("update", "ghost", "--role", "admin"));
    assertEquals("Unknown FDE 'ghost'.", refusal);
  }

  @Test
  void keyVerbsRegisterListAndRemoveAnFdesKeysOnThisBox() {
    var desk = SshPublicKey.parse(TestSshKeys.ed25519("desk", "me@desk"));
    assertEquals(0, fde("add", "ada"));

    assertEquals(0, fde("key", "list"));
    assertEquals(List.of("  No SSH keys. Register one with 'sail fde key add'."), printed());

    assertEquals(0, fde("key", "add", "ada", LAPTOP.line()));
    assertEquals("  ✓ Registered key for ada: " + LAPTOP.fingerprint(), printed().getFirst());
    assertEquals(1, fde("key", "add", "ada", LAPTOP.line()));
    assertEquals("That key (" + LAPTOP.fingerprint() + ") is already registered.", refusal);
    assertEquals(1, fde("key", "add", "ghost", desk.line()));
    assertEquals("Unknown FDE 'ghost'. Add it with 'sail fde add ghost'.", refusal);

    assertEquals(0, fde("key", "list", "ada"));
    assertTrue(printed().stream().anyMatch(line -> line.contains(LAPTOP.fingerprint())));
    assertEquals(1, fde("key", "list", "ghost"));
    assertEquals("Unknown FDE 'ghost'.", refusal);

    assertEquals(0, fde("key", "add", "ada", desk.line()));
    assertEquals(0, fde("key", "rm", "ada"));
    assertEquals(
        List.of(
            "  'ada' has 2 keys; specify one:",
            "    sail fde key rm " + LAPTOP.fingerprint(),
            "    sail fde key rm " + desk.fingerprint()),
        printed());
    assertEquals(0, fde("key", "rm", desk.fingerprint()));
    assertEquals("  ✓ Removed key " + desk.fingerprint(), printed().getFirst());
    assertEquals(0, fde("key", "rm", "ada"));
    assertEquals("  ✓ Removed key " + LAPTOP.fingerprint(), printed().getFirst());
    assertEquals(List.of(), inDb(db -> new FdeSshKeyStore(db).list()));

    assertEquals(0, fde("key", "rm", "ada"));
    assertEquals(List.of("  ⚠ No SSH keys registered for 'ada'."), printed());
    assertEquals(0, fde("key", "rm", "SHA256:nope"));
    assertEquals(List.of("  ⚠ No key with fingerprint SHA256:nope"), printed());
    assertEquals(1, fde("key", "rm", "ghost"));
    assertEquals(
        "'ghost' is neither a known FDE handle nor a SHA256: fingerprint. See 'sail fde key"
            + " list'.",
        refusal);
  }

  @Test
  void passkeyVerbsListAndRevokeAnFdesPasskeysAndOptionallyItsSessions() {
    assertEquals(0, fde("add", "ada"));
    var fdeId = inDb(db -> new FdeStore(db).byHandle("ada").orElseThrow().id());
    var id = "alpha".getBytes(StandardCharsets.UTF_8);

    assertEquals(0, fde("passkey", "list", "ada"));
    assertEquals(
        List.of("  No passkeys for 'ada'. Enroll one with 'sail fde enroll ada'."), printed());
    assertEquals(1, fde("passkey", "list", "ghost"));
    assertEquals("Unknown FDE 'ghost'.", refusal);

    inDb(
        db -> {
          new WebauthnCredentialStore(db)
              .save(
                  new RegisteredCredential(id, new byte[] {1}, -7, 0, null, false, false),
                  fdeId,
                  "macbook");
          return new AuthSessionStore(db).create(fdeId, Duration.ofHours(1));
        });
    assertEquals(0, fde("passkey", "list", "ada"));
    assertTrue(printed().stream().anyMatch(line -> line.contains("macbook")), printed().toString());

    assertEquals(1, fde("passkey", "rm", "ada", "--credential", "zz"));
    assertEquals(
        FdeCommand.Passkey.noMatchMessage(
            "ada",
            "zz",
            inDb(db -> new WebauthnCredentialStore(db).listForFde(fdeId)),
            OperatorCommand.PLAIN),
        refusal);

    assertEquals(
        0, fde("passkey", "rm", "ada", "--credential", Passkeys.encodeId(id), "--revoke-sessions"));

    assertEquals(
        List.of(
            "  ✓ Revoked passkey 'macbook' (" + Passkeys.shortId(id) + ") of ada",
            "  ✓ Revoked 1 active web session(s) of ada",
            "  'ada' has no passkeys left. Re-enroll with 'sail fde enroll ada'."),
        printed());
    assertEquals(List.of(), inDb(db -> new WebauthnCredentialStore(db).listForFde(fdeId)));
  }

  @Test
  void passkeyNoMatchMessageListsRegisteredCredentials() {
    var credentials = List.of(credential("alpha", "macbook"), credential("bravo", null));
    var message =
        FdeCommand.Passkey.noMatchMessage("uday", "zz", credentials, OperatorCommand.PLAIN);
    assertTrue(message.contains("No passkey of 'uday' matches 'zz'"));
    assertTrue(message.contains(Passkeys.encodeId("alpha".getBytes(StandardCharsets.UTF_8))));
    assertTrue(message.contains("macbook"));
    assertTrue(message.contains("passkey · 2026-07-06"), "unlabeled rows show the default label");
  }

  @Test
  void passkeyNoMatchMessagePointsAtEnrollWhenNoneRegistered() {
    var message = FdeCommand.Passkey.noMatchMessage("uday", "zz", List.of(), OperatorCommand.PLAIN);
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
