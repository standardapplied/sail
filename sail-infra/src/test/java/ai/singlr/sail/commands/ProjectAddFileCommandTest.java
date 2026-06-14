/*
 * Copyright (c) 2026 Standard Applied Intelligence Labs
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.sail.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ProjectAddFileCommandTest {

  @Nested
  class ParseSelection {

    @Test
    void singleNumber() {
      assertEquals(List.of(1), ProjectAddFileCommand.parseSelection("1", 5));
    }

    @Test
    void commaSeparated() {
      assertEquals(List.of(1, 3, 5), ProjectAddFileCommand.parseSelection("1,3,5", 5));
    }

    @Test
    void range() {
      assertEquals(List.of(1, 2, 3), ProjectAddFileCommand.parseSelection("1-3", 5));
    }

    @Test
    void mixedRangeAndNumbers() {
      assertEquals(List.of(1, 2, 3, 5), ProjectAddFileCommand.parseSelection("1-3,5", 5));
    }

    @Test
    void all() {
      assertEquals(List.of(1, 2, 3, 4, 5), ProjectAddFileCommand.parseSelection("all", 5));
    }

    @Test
    void allCaseInsensitive() {
      assertEquals(List.of(1, 2, 3), ProjectAddFileCommand.parseSelection("ALL", 3));
    }

    @Test
    void filtersOutOfRange() {
      assertEquals(List.of(3), ProjectAddFileCommand.parseSelection("0,3,10", 5));
    }

    @Test
    void rangeClampedToMax() {
      assertEquals(List.of(3, 4, 5), ProjectAddFileCommand.parseSelection("3-8", 5));
    }

    @Test
    void duplicatesRemoved() {
      assertEquals(List.of(1, 2, 3), ProjectAddFileCommand.parseSelection("1,2,1,3,2", 5));
    }

    @Test
    void resultIsSorted() {
      assertEquals(List.of(1, 3, 5), ProjectAddFileCommand.parseSelection("5,1,3", 5));
    }

    @Test
    void invalidInputReturnsEmpty() {
      assertEquals(List.of(), ProjectAddFileCommand.parseSelection("abc", 5));
    }

    @Test
    void emptyInputReturnsEmpty() {
      assertEquals(List.of(), ProjectAddFileCommand.parseSelection("", 5));
    }

    @Test
    void spacesAroundNumbers() {
      assertEquals(List.of(1, 3), ProjectAddFileCommand.parseSelection(" 1 , 3 ", 5));
    }

    @Test
    void spacesAroundRange() {
      assertEquals(List.of(2, 3, 4), ProjectAddFileCommand.parseSelection(" 2 - 4 ", 5));
    }

    @Test
    void singleItemMaxOne() {
      assertEquals(List.of(1), ProjectAddFileCommand.parseSelection("1", 1));
    }

    @Test
    void rangeStartEqualsEnd() {
      assertEquals(List.of(3), ProjectAddFileCommand.parseSelection("3-3", 5));
    }
  }

  @Nested
  class ParseDiscoveryOutput {

    @Test
    void stripsWorkspacePrefix() {
      var output = "/home/dev/workspace/outline/.env\n/home/dev/workspace/scripts/setup.sh\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/dev/workspace");

      assertEquals(List.of("outline/.env", "scripts/setup.sh"), result);
    }

    @Test
    void sortsResults() {
      var output = "/home/dev/workspace/z-app/.env\n/home/dev/workspace/a-app/.env\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/dev/workspace");

      assertEquals(List.of("a-app/.env", "z-app/.env"), result);
    }

    @Test
    void handlesBlankLines() {
      var output = "/home/dev/workspace/app/.env\n\n  \n/home/dev/workspace/app/.conf\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/dev/workspace");

      assertEquals(List.of("app/.conf", "app/.env"), result);
    }

    @Test
    void returnsEmptyForNullOutput() {
      assertEquals(
          List.of(), ProjectAddFileCommand.parseDiscoveryOutput(null, "/home/dev/workspace"));
    }

    @Test
    void returnsEmptyForBlankOutput() {
      assertEquals(
          List.of(), ProjectAddFileCommand.parseDiscoveryOutput("  ", "/home/dev/workspace"));
    }

    @Test
    void handlesCustomSshUser() {
      var output = "/home/alice/workspace/app/.env\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/alice/workspace");

      assertEquals(List.of("app/.env"), result);
    }

    @Test
    void preservesNestedPaths() {
      var output = "/home/dev/workspace/a/b/c/deep.conf\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/dev/workspace");

      assertEquals(List.of("a/b/c/deep.conf"), result);
    }

    @Test
    void handlesPathWithoutPrefix() {
      var output = "relative/path/file.env\n";

      var result = ProjectAddFileCommand.parseDiscoveryOutput(output, "/home/dev/workspace");

      assertEquals(List.of("relative/path/file.env"), result);
    }
  }

  @Nested
  class BuildFindArgs {

    @Test
    void defaultPatternsIncludeCommonConfigFiles() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertTrue(args.contains("-name"));
      assertTrue(args.contains(".env"));
      assertTrue(args.contains(".env.*"));
      assertTrue(args.contains("*.properties"));
      assertTrue(args.contains("*.conf"));
      assertTrue(args.contains("*.sh"));
      assertTrue(args.contains("docker-compose*.yml"));
    }

    @Test
    void startsWithFindAndWorkspaceDir() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertEquals("find", args.get(0));
      assertEquals("/home/dev/workspace", args.get(1));
    }

    @Test
    void limitsDepthToFive() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      var maxdepthIdx = args.indexOf("-maxdepth");
      assertTrue(maxdepthIdx > 0);
      assertEquals("5", args.get(maxdepthIdx + 1));
    }

    @Test
    void searchesOnlyFiles() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      var typeIdx = args.indexOf("-type");
      assertTrue(typeIdx > 0);
      assertEquals("f", args.get(typeIdx + 1));
    }

    @Test
    void customPatternReplacesDefaults() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", "*.json");

      assertTrue(args.contains("*.json"));
      assertFalse(args.contains(".env"));
      assertFalse(args.contains("*.properties"));
    }

    @Test
    void excludesGitDirectory() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertTrue(args.contains("*/.git/*"));
    }

    @Test
    void excludesNodeModules() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertTrue(args.contains("*/node_modules/*"));
    }

    @Test
    void excludesBuildDirectories() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertTrue(args.contains("*/target/*"));
      assertTrue(args.contains("*/build/*"));
    }

    @Test
    void resultIsImmutable() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertThrows(UnsupportedOperationException.class, () -> args.add("extra"));
    }

    @Test
    void customPatternWithCustomUser() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/alice/workspace", "setup.*");

      assertEquals("/home/alice/workspace", args.get(1));
      assertTrue(args.contains("setup.*"));
    }

    @Test
    void containsParenGrouping() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      assertTrue(args.contains("("));
      assertTrue(args.contains(")"));
    }

    @Test
    void excludeDirectoriesUseNegation() {
      var args = ProjectAddFileCommand.buildFindArgs("/home/dev/workspace", null);

      for (var dir : ProjectAddFileCommand.EXCLUDED_DIRS) {
        var dirPattern = "*/" + dir + "/*";
        var idx = args.indexOf(dirPattern);
        assertTrue(idx > 0, "Missing exclusion for " + dir);
        assertEquals("!", args.get(idx - 2));
        assertEquals("-path", args.get(idx - 1));
      }
    }
  }

  @Nested
  class DefaultPatterns {

    @Test
    void includesEnvFiles() {
      assertTrue(ProjectAddFileCommand.DEFAULT_PATTERNS.contains(".env"));
      assertTrue(ProjectAddFileCommand.DEFAULT_PATTERNS.contains(".env.*"));
    }

    @Test
    void includesShellScripts() {
      assertTrue(ProjectAddFileCommand.DEFAULT_PATTERNS.contains("*.sh"));
    }

    @Test
    void includesDockerCompose() {
      assertTrue(ProjectAddFileCommand.DEFAULT_PATTERNS.contains("docker-compose*.yml"));
      assertTrue(ProjectAddFileCommand.DEFAULT_PATTERNS.contains("docker-compose*.yaml"));
    }

    @Test
    void isImmutable() {
      assertThrows(
          UnsupportedOperationException.class,
          () -> ProjectAddFileCommand.DEFAULT_PATTERNS.add("extra"));
    }
  }

  @Nested
  class ExcludedDirs {

    @Test
    void excludesGitAndBuildDirs() {
      var dirs = ProjectAddFileCommand.EXCLUDED_DIRS;
      assertTrue(dirs.contains(".git"));
      assertTrue(dirs.contains("node_modules"));
      assertTrue(dirs.contains("target"));
      assertTrue(dirs.contains("build"));
    }

    @Test
    void excludesCacheDirs() {
      var dirs = ProjectAddFileCommand.EXCLUDED_DIRS;
      assertTrue(dirs.contains(".m2"));
      assertTrue(dirs.contains(".cache"));
      assertTrue(dirs.contains(".gradle"));
    }

    @Test
    void excludesVendor() {
      assertTrue(ProjectAddFileCommand.EXCLUDED_DIRS.contains("vendor"));
    }

    @Test
    void isImmutable() {
      assertThrows(
          UnsupportedOperationException.class,
          () -> ProjectAddFileCommand.EXCLUDED_DIRS.add("extra"));
    }
  }
}
