/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.store;

import ai.singlr.sail.common.Strings;
import ai.singlr.sail.harness.Harness;
import ai.singlr.sail.harness.Harnesses;
import ai.singlr.sail.harness.LoginKind;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The harness logins people store for their runs, kept outside the database in root-only files on
 * this runner and never synced: a login must be replayed to its harness, so it cannot be hashed as
 * every bearer secret in the database is. A login is {@code <root>/<fde-id>/<harness>/<name>}, its
 * kind on the first line and its value on the second; {@code <harness>/.default} names the default,
 * a name no login can take. Directories are {@code 0700} and files {@code 0600}, each written whole
 * as a temporary file and renamed into place; the stored date is the file's modification time. It
 * is keyed by FDE id, not handle, since a handle can be given again once its person is removed.
 */
public final class LoginStore {

  /** The name a login is stored under when none is given. */
  public static final String DEFAULT_NAME = "default";

  /** The longest value a login holds. */
  static final int MAX_VALUE_BYTES = 16 * 1024;

  private static final String DEFAULT_FILE = ".default";
  private static final String TEMP_PREFIX = ".tmp-";
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
  private static final Pattern FDE_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
  private static final Pattern VALUE = Pattern.compile("[\\x21-\\x7E&&[^\"'\\\\$`]]+");
  private static final Set<PosixFilePermission> DIRECTORY =
      PosixFilePermissions.fromString("rwx------");
  private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");
  private static final Object WRITES = new Object();

  /**
   * One stored login as anyone may be shown it: never its value, only the first eight hex digits of
   * its SHA-256, which tell two logins apart and reveal nothing of either.
   */
  public record Row(
      String harness,
      String name,
      String kind,
      boolean isDefault,
      Instant storedAt,
      String fingerprint) {}

  /**
   * A stored login as a launch hands it to its run: its row, the kind its harness reads it as, and
   * the value, which its {@link #toString} never shows.
   */
  public record Login(Row row, LoginKind kind, String value) {
    @Override
    public String toString() {
      return "Login[" + row.harness() + "/" + row.name() + " " + row.fingerprint() + "]";
    }
  }

  private final Path root;

  /** Logins kept under {@code root}, made {@code 0700} when the first login is stored. */
  public LoginStore(Path root) {
    this.root = root;
  }

  /**
   * The logins of the people {@code db} holds, kept beside its file: on a box {@code
   * /var/lib/sail/logins}, root's alone. A database held in memory keeps none.
   */
  public static LoginStore beside(Sqlite db) {
    var file = db.path();
    return new LoginStore(file == null ? null : file.getParent().resolve("logins"));
  }

  /**
   * Stores {@code value} as {@code fdeId}'s login {@code name} for {@code harness}, of {@code kind}
   * (its harness's first when blank), replacing a login of that name. The first login stored for a
   * harness, or one stored while none is the default, becomes the default.
   *
   * @throws IllegalArgumentException naming the rule a name, kind or value breaks, never the value
   */
  public Row store(String fdeId, Harness harness, String name, String kind, String value) {
    var named = requireName(name);
    var declared = harness.loginKind(kind);
    requireValue(value);
    synchronized (WRITES) {
      var dir = directory(fdeId, harness);
      write(dir, named, declared.kind() + "\n" + value + "\n");
      if (defaultName(dir).filter(current -> Files.exists(dir.resolve(current))).isEmpty()) {
        write(dir, DEFAULT_FILE, named + "\n");
      }
      return row(dir, harness, named).orElseThrow();
    }
  }

  /** Every login {@code fdeId} keeps, by harness and then name. */
  public List<Row> list(String fdeId) {
    var person = person(fdeId);
    if (person == null || !Files.isDirectory(person)) {
      return List.of();
    }
    var rows = new ArrayList<Row>();
    for (var harness : harnessDirectories(person)) {
      for (var name : names(harness)) {
        known(harness).flatMap(known -> row(harness, known, name)).ifPresent(rows::add);
      }
    }
    rows.sort(Comparator.comparing(Row::harness).thenComparing(Row::name));
    return List.copyOf(rows);
  }

  /**
   * Makes {@code name} {@code fdeId}'s default login for {@code harness}: a launch after this one
   * starts with it, and a run already live keeps the one it started with.
   *
   * @throws NoSuchElementException when no such login is stored
   */
  public Row use(String fdeId, Harness harness, String name) {
    var named = requireName(name);
    synchronized (WRITES) {
      var stored = requireStored(fdeId, harness, named);
      write(dirOf(fdeId, harness), DEFAULT_FILE, named + "\n");
      return new Row(
          stored.harness(), named, stored.kind(), true, stored.storedAt(), stored.fingerprint());
    }
  }

  /**
   * Removes {@code fdeId}'s login {@code name} for {@code harness}, and the default with it when it
   * was the default: no other login becomes the default on its own.
   *
   * @throws NoSuchElementException when no such login is stored
   */
  public Row remove(String fdeId, Harness harness, String name) {
    var named = requireName(name);
    synchronized (WRITES) {
      var removed = requireStored(fdeId, harness, named);
      var dir = dirOf(fdeId, harness);
      delete(dir.resolve(named));
      if (removed.isDefault()) {
        delete(dir.resolve(DEFAULT_FILE));
      }
      return removed;
    }
  }

  /** {@code fdeId}'s default login for {@code harness}, value and all, or empty for none. */
  public Optional<Login> defaultFor(String fdeId, Harness harness) {
    var dir = dirOf(fdeId, harness);
    if (dir == null) {
      return Optional.empty();
    }
    return defaultName(dir).flatMap(name -> login(dir, harness, name));
  }

  private Optional<Login> login(Path dir, Harness harness, String name) {
    return readLogin(dir.resolve(name))
        .map(
            lines ->
                new Login(
                    row(dir, harness, name, lines), harness.loginKind(lines.get(0)), lines.get(1)));
  }

  /**
   * Deletes every login {@code fdeId} kept: what removing a person leaves of them on this runner.
   */
  public void forget(String fdeId) {
    var person = person(fdeId);
    if (person != null) {
      deleteTree(person);
    }
  }

  /**
   * Deletes the logins of every person {@code fdeIds} does not name: the people removed while a
   * removal's own deletion did not run. Returns how many people's logins it deleted.
   */
  public int sweep(Collection<String> fdeIds) {
    if (root == null || !Files.isDirectory(root)) {
      return 0;
    }
    var swept = 0;
    for (var person : children(root)) {
      if (!fdeIds.contains(person.getFileName().toString())) {
        deleteTree(person);
        swept++;
      }
    }
    return swept;
  }

  /** The first eight hex digits of the SHA-256 of {@code value}. */
  static String fingerprint(String value) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, 8);
    } catch (NoSuchAlgorithmException absent) {
      throw new IllegalStateException("SHA-256 is not available.", absent);
    }
  }

  /**
   * {@code name} once it is a login's name.
   *
   * @throws IllegalArgumentException naming the rule
   */
  public static String requireName(String name) {
    var named = Strings.isBlank(name) ? DEFAULT_NAME : name;
    if (!NAME.matcher(named).matches()) {
      throw new IllegalArgumentException(
          "A login's name is 1 to 32 lowercase letters, digits and hyphens, starting with a letter"
              + " or digit, such as work or personal.");
    }
    return named;
  }

  /**
   * Refuses a value no launch could hand its harness as it is: it is one line, not blank, at most
   * 16 KiB, of printable ASCII with no space, quote, backslash, {@code $} or backtick, so nothing
   * that reads it ever quotes or evaluates it. The refusal names the rule, never the value.
   */
  static void requireValue(String value) {
    if (Strings.isBlank(value)) {
      throw new IllegalArgumentException("A login's value is empty: paste the key or token.");
    }
    if (value.contains("\n") || value.contains("\r")) {
      throw new IllegalArgumentException(
          "A login's value is one line: paste only the key or token.");
    }
    if (value.getBytes(StandardCharsets.UTF_8).length > MAX_VALUE_BYTES) {
      throw new IllegalArgumentException("A login's value is at most 16 KiB.");
    }
    if (!VALUE.matcher(value).matches()) {
      throw new IllegalArgumentException(
          "A login's value is printable ASCII with no space, quote, backslash, $ or backtick.");
    }
  }

  private Path person(String fdeId) {
    if (root == null) {
      return null;
    }
    if (fdeId == null || !FDE_ID.matcher(fdeId).matches()) {
      throw new IllegalArgumentException("Not an FDE id: '" + fdeId + "'.");
    }
    return root.resolve(fdeId);
  }

  private Path dirOf(String fdeId, Harness harness) {
    var person = person(fdeId);
    return person == null ? null : person.resolve(harness.yamlName());
  }

  private Path directory(String fdeId, Harness harness) {
    var person = person(fdeId);
    if (person == null) {
      throw new IllegalStateException(
          "This box keeps its database in memory, so it keeps no logins.");
    }
    var dir = person.resolve(harness.yamlName());
    for (var each : List.of(root, person, dir)) {
      privateDirectory(each);
    }
    return dir;
  }

  private Row requireStored(String fdeId, Harness harness, String name) {
    var dir = dirOf(fdeId, harness);
    return Optional.ofNullable(dir)
        .flatMap(stored -> row(stored, harness, name))
        .orElseThrow(
            () ->
                new NoSuchElementException(
                    "No " + harness.displayName() + " login named '" + name + "' is stored."));
  }

  private Optional<Row> row(Path dir, Harness harness, String name) {
    return readLogin(dir.resolve(name)).map(lines -> row(dir, harness, name, lines));
  }

  private static Row row(Path dir, Harness harness, String name, List<String> lines) {
    return new Row(
        harness.yamlName(),
        name,
        lines.get(0),
        defaultName(dir).filter(name::equals).isPresent(),
        modified(dir.resolve(name)),
        fingerprint(lines.get(1)));
  }

  private static Optional<String> defaultName(Path dir) {
    return read(dir.resolve(DEFAULT_FILE)).filter(lines -> lines.size() == 1).map(List::getFirst);
  }

  /** A login file's two lines, its kind and its value, or empty when it is not one. */
  private static Optional<List<String>> readLogin(Path file) {
    return read(file).filter(lines -> lines.size() == 2);
  }

  private static Optional<List<String>> read(Path file) {
    try {
      return Optional.of(Files.readAllLines(file, StandardCharsets.UTF_8));
    } catch (NoSuchFileException absent) {
      return Optional.empty();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file + ".", e);
    }
  }

  private static Instant modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read " + file + ".", e);
    }
  }

  private static Optional<Harness> known(Path dir) {
    var name = dir.getFileName().toString();
    return Harnesses.all().stream().filter(harness -> harness.yamlName().equals(name)).findFirst();
  }

  private static List<Path> harnessDirectories(Path person) {
    return children(person).stream().filter(Files::isDirectory).toList();
  }

  private static List<String> names(Path dir) {
    return children(dir).stream()
        .map(file -> file.getFileName().toString())
        .filter(name -> NAME.matcher(name).matches())
        .toList();
  }

  private static List<Path> children(Path dir) {
    try (Stream<Path> listed = Files.list(dir)) {
      return listed.sorted().toList();
    } catch (NoSuchFileException absent) {
      return List.of();
    } catch (IOException e) {
      throw new UncheckedIOException("Could not list " + dir + ".", e);
    }
  }

  private static void write(Path dir, String name, String content) {
    try {
      var temp =
          Files.createTempFile(dir, TEMP_PREFIX, "", PosixFilePermissions.asFileAttribute(FILE));
      try {
        Files.writeString(temp, content, StandardCharsets.UTF_8);
        Files.move(
            temp,
            dir.resolve(name),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Could not write a login under " + dir + ".", e);
    }
  }

  private static void privateDirectory(Path dir) {
    try {
      Files.createDirectory(dir, PosixFilePermissions.asFileAttribute(DIRECTORY));
    } catch (FileAlreadyExistsException present) {
      setPrivate(dir);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not create " + dir + ".", e);
    }
  }

  private static void setPrivate(Path dir) {
    try {
      Files.setPosixFilePermissions(dir, DIRECTORY);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not make " + dir + " private.", e);
    }
  }

  private static void delete(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not delete " + file + ".", e);
    }
  }

  private static void deleteTree(Path dir) {
    if (!Files.isDirectory(dir)) {
      return;
    }
    for (var child : children(dir)) {
      if (Files.isDirectory(child)) {
        deleteTree(child);
      } else {
        delete(child);
      }
    }
    delete(dir);
  }
}
