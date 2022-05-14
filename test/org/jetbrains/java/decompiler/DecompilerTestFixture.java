// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.java.decompiler;

import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.decompiler.PrintStreamLogger;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.util.InterpreterUtil;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DecompilerTestFixture {
  private Path testDataDir;
  private Path tempDir;
  private Path targetDir;
  private TestConsoleDecompiler decompiler;
  private boolean cleanup = true;

  public DecompilerTestFixture() {
    testDataDir = Paths.get("testData");
    if (!isTestDataDir(testDataDir)) testDataDir = Paths.get("community/plugins/java-decompiler/engine/testData");
    if (!isTestDataDir(testDataDir)) testDataDir = Paths.get("plugins/java-decompiler/engine/testData");
    if (!isTestDataDir(testDataDir)) testDataDir = Paths.get("../community/plugins/java-decompiler/engine/testData");
    if (!isTestDataDir(testDataDir)) testDataDir = Paths.get("../plugins/java-decompiler/engine/testData");
    testDataDir = testDataDir.toAbsolutePath();
  }

  public void setUp(Object... optionPairs) throws IOException {
    assertEquals(0, optionPairs.length % 2);

    assertTrue(isTestDataDir(testDataDir), "current dir: " + new File("").getAbsolutePath());

    tempDir = Files.createTempDirectory("decompiler_test_");

    targetDir = tempDir.resolve("decompiled");
    Files.createDirectories(targetDir);

    Map<String, Object> options = new HashMap<>();
    options.put(IFernflowerPreferences.LOG_LEVEL, "warn");
    options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
    options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
    options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
    options.put(IFernflowerPreferences.LITERALS_AS_IS, "1");
    options.put(IFernflowerPreferences.UNIT_TEST_MODE, "1");
    options.put(IFernflowerPreferences.ERROR_MESSAGE, "");
    for (int i = 0; i < optionPairs.length; i += 2) {
      options.put((String) optionPairs[i], optionPairs[i + 1]);
    }
    decompiler = new TestConsoleDecompiler(targetDir.toFile(), options);
  }

  public void tearDown() {
    if (tempDir != null && cleanup) {
      delete(tempDir);
    }
    decompiler.close();
  }

  public Path getTestDataDir() {
    return testDataDir;
  }

  public Path getTempDir() {
    return tempDir;
  }

  public Path getTargetDir() {
    return targetDir;
  }

  public ConsoleDecompiler getDecompiler() {
    return decompiler;
  }

  public void setCleanup(boolean value) {
    this.cleanup = value;
  }

  public boolean getCleanup() {
    return cleanup;
  }

  private static boolean isTestDataDir(Path dir) {
    return Files.isDirectory(dir) && Files.isDirectory(dir.resolve("classes")) && Files.isDirectory(dir.resolve("results"));
  }

  private static void delete(Path file) {
    try {
      Files.walkFileTree(file, new SimpleFileVisitor<Path>() {
        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
          Files.delete(file);
          return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
          if (exc != null) throw exc;
          Files.delete(dir);
          return FileVisitResult.CONTINUE;
        }
      });
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static void assertFilesEqual(Path expected, Path actual) {
    try {
      if (Files.isDirectory(expected)) {
        Path[] children = Files.list(expected).map(Path::getFileName).toArray(Path[]::new);
        assertThat(Files.list(actual).map(Path::getFileName).toArray(Path[]::new), arrayContainingInAnyOrder(children));
        for (Path name : children) {
          assertFilesEqual(expected.resolve(name), actual.resolve(name));
        }
      }
      else {
        assertEquals(getContent(expected), getContent(actual));
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static String getContent(Path expected) {
    try {
      return new String(Files.readAllBytes(expected), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
    catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  // cache zip files
  private static class TestConsoleDecompiler extends ConsoleDecompiler {
    private final HashMap<String, ZipFile> zipFiles = new HashMap<>();

    TestConsoleDecompiler(File destination, Map<String, Object> options) {
      super(destination, options, new PrintStreamLogger(System.out), SaveType.LEGACY_CONSOLEDECOMPILER);
    }

    @Override
    public byte[] getBytecode(String externalPath, String internalPath) throws IOException {
      File file = new File(externalPath);
      if (internalPath == null) {
        return InterpreterUtil.getBytes(file);
      } else {
        ZipFile archive = zipFiles.get(file.getName());
        if (archive == null) {
          archive = new ZipFile(file);
          zipFiles.put(file.getName(), archive);
        }
        ZipEntry entry = archive.getEntry(internalPath);
        if (entry == null) throw new IOException("Entry not found: " + internalPath);
        return InterpreterUtil.getBytes(archive, entry);
      }
    }

    @Override
    public void close() {
      try {
        super.close();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }

      for (ZipFile file : zipFiles.values()) {
        try {
          file.close();
        }
        catch (IOException e) {
          e.printStackTrace();
        }
      }
      zipFiles.clear();
    }
  }
}