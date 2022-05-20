/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.java.decompiler;

import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.jetbrains.java.decompiler.util.TextBuffer;
import org.junit.jupiter.api.*;
import org.opentest4j.AssertionFailedError;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.jetbrains.java.decompiler.DecompilerTestFixture.assertFilesEqual;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/*
 * Set individual test duration time limit to 60 seconds.
 * This will help us to test bugs hanging decompiler.
 */
@Timeout(60)
public abstract class SingleClassesTestBase {
  private TestSet currentTestSet;
  private final List<TestSet> testSets = new ArrayList<>();
  private final Set<String> classNames = new HashSet<>();

  protected String[] getDecompilerOptions() {
    return new String[] {};
  }

  protected abstract void registerAll();

  protected final void registerSet(String name, Runnable initializer, Object ...options) {
    currentTestSet = new TestSet(name, options);
    initializer.run();
    testSets.add(currentTestSet);
  }

  protected final void register(TestDefinition.Version version, String testClass, String... others) {
    register(version, testClass, false, others);
  }

  private void register(TestDefinition.Version version, String testClass, boolean failable, String... others) {
    if (classNames.contains(testClass)) {
      throw new AssertionFailedError("Registered same class twice! " + testClass);
    }
    classNames.add(testClass);

    List<String> othersList = new ArrayList<>(others.length);
    for (String other : others) othersList.add(getFullClassName(other));
    currentTestSet.testDefinitions.add(new TestDefinition(version, getFullClassName(testClass), othersList, failable));
  }

  @Deprecated
  // Temporary fix for inconsistent javac code generation
  protected final void registerFailable(TestDefinition.Version version, String testClass, String... others) {
    register(version, testClass, true, others);
  }

  protected final void registerRaw(TestDefinition.Version version, String testClass, String ...others) {
    currentTestSet.testDefinitions.add(new TestDefinition(version, testClass, Arrays.asList(others), false));
  }

  private static String getFullClassName(String className) {
    if (!className.contains("/")) return "pkg/" + className;
    return className;
  }

  @TestFactory
  @DisplayName("Test single classes")
  public Stream<DynamicContainer> testRegistered() {
    testSets.clear();
    registerAll();
    return testSets.stream()
      .filter(s -> !s.testDefinitions.isEmpty())
      .map(s -> DynamicContainer.dynamicContainer(s.name, s.getTests()));
  }

  protected static Path getClassFile(DecompilerTestFixture fixture, TestDefinition.Version version, String name) {
    return fixture.getTestDataDir().resolve("classes/" + version.directory + "/" + name + ".class");
  }

  protected static Path getReferenceFile(DecompilerTestFixture fixture, String testClass) {
    return fixture.getTestDataDir().resolve("results/" + testClass + ".dec");
  }

  private static List<Path> collectClasses(Path classFile) {
    List<Path> files = new ArrayList<>();
    files.add(classFile);

    Path parent = classFile.getParent();
    if (parent != null) {
      final Pattern pattern = Pattern.compile(classFile.getFileName().toString().replace(".class", "") + "\\$.+\\.class");
      try {
        Files.list(parent).filter(p -> pattern.matcher(p.getFileName().toString()).matches()).forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    return files;
  }

  static class TestSet {
    public final String name;
    public final Object[] options;
    public final List<TestDefinition> testDefinitions = new ArrayList<>();

    public TestSet(String name, Object[] options) {
      this.name = name;
      this.options = options;
    }

    public Stream<DynamicNode> getTests() {
      Map<TestDefinition.Version, List<DynamicTest>> tests = new EnumMap<>(TestDefinition.Version.class);
      for (TestDefinition def : testDefinitions) {
        String name = def.testClass;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        Path classFile = def.getClassFile();
        Path ref = def.getReferenceFile();

        // Inject a specific runtime
        DynamicTest test = DynamicTest.dynamicTest(name, Files.exists(ref) ? ref.toUri() : classFile.toUri(), () -> {
          Object[] options = this.options;
          if (def.version.runtimeVersion != TestDefinition.Version.UNKNOWN_RUNTIME) {
            for (int i = 0; i < options.length; i+= 2) {
              if (options[i].equals(IFernflowerPreferences.INCLUDE_JAVA_RUNTIME)
                && (options[i + 1].equals("1") || options[i + 1].equals("current"))) {
                final String versionJavaHome = System.getProperty("java." + def.version.runtimeVersion + ".home");
                if (versionJavaHome == null) {
                  throw new IllegalStateException("No Java runtime was provided at system property java." + def.version.runtimeVersion + ".home");
                }
                options = Arrays.copyOf(options, options.length);
                options[i + 1] = versionJavaHome;
              }
            }
          }
          def.run(options);
        });
        tests.computeIfAbsent(def.version, k -> new ArrayList<>()).add(test);
      }
      Path baseDir = new DecompilerTestFixture().getTestDataDir().resolve("classes/");
      return tests.entrySet().stream().map(e -> {
        TestDefinition.Version version = e.getKey();
        return DynamicContainer.dynamicContainer(
          version.toString(),
          baseDir.resolve(version.directory).toUri(),
          e.getValue().stream()
        );
      });
    }
  }

  static class TestDefinition {
    public final Version version;
    public final String testClass;
    public final List<String> others;
    public final boolean failable;
    private final DecompilerTestFixture fixture = new DecompilerTestFixture();

    public TestDefinition(Version version, String testClass, List<String> others, boolean failable) {
      this.version = version;
      this.testClass = testClass;
      this.others = others;
      this.failable = failable;
    }

    public Path getClassFile() {
      return SingleClassesTestBase.getClassFile(fixture, version, testClass);
    }

    public Path getReferenceFile() {
      return SingleClassesTestBase.getReferenceFile(fixture, testClass);
    }

    public void run(Object[] options) throws IOException {
      fixture.setUp(options);
      ConsoleDecompiler decompiler = fixture.getDecompiler();
      Path classFile = getClassFile();
      assertTrue(Files.isRegularFile(classFile), classFile + " should exist");
      for (Path file : collectClasses(classFile)) {
        decompiler.addSource(file.toFile());
      }

      for (String companionFile : others) {
        Path companionClassFile = SingleClassesTestBase.getClassFile(fixture, version, companionFile);
        assertTrue(Files.isRegularFile(companionClassFile), companionFile + " should exist");
        for (Path file : collectClasses(companionClassFile)) {
          decompiler.addSource(file.toFile());
        }
      }

      decompiler.decompileContext();

      TextBuffer.checkLeaks();

      String testFileName = classFile.getFileName().toString();
      String testName = testFileName.substring(0, testFileName.length() - 6);
      Path decompiledFile = fixture.getTargetDir().resolve(testName + ".java");
      assertTrue(Files.isRegularFile(decompiledFile));
      Path referenceFile = getReferenceFile();
      if (!Files.exists(referenceFile)) {
        try {
          Files.createDirectories(referenceFile.getParent());
          Files.copy(decompiledFile, referenceFile);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
        //noinspection ConstantConditions
        assumeTrue(false, referenceFile.getFileName() + " was not present yet");
      } else {
        try {
          assertTrue(Files.isRegularFile(referenceFile));
          assertFilesEqual(referenceFile, decompiledFile);
        } catch (AssertionFailedError e) {
          if (failable) {
            assumeTrue(false, referenceFile.getFileName() + " failed but was ignored");
          } else {
            throw e;
          }
        }
      }
      fixture.tearDown();
    }

    enum Version {
      CUSTOM("custom", "Custom"),
      JAVA_8(8),
      JAVA_8_NODEBUG(8, "nodebug", "No Debug Info"),
      JAVA_9(9),
      JAVA_11(11),
      JAVA_16(16),
      JAVA_16_PREVIEW(16, "preview", "Preview"),
      JAVA_16_NODEBUG(16, "nodebug", "No Debug Info"),
      JAVA_17(17),
      JAVA_17_PREVIEW(17, "preview", "Preview"),
      GROOVY("groovy", "Groovy"),
      KOTLIN("kt", "Kotlin"),
      JASM("jasm", "Custom (jasm)"),
      ;

      public static final int UNKNOWN_RUNTIME = -1;

      public final int runtimeVersion;
      public final String directory;
      public final String display;

      Version(String directory, String display) {
        this.runtimeVersion = UNKNOWN_RUNTIME;
        this.directory = directory;
        this.display = display;
      }

      Version(int javaVersion) {
        this(javaVersion, "", "");
      }

      Version(int javaVersion, String suffix, String display) {
        this.runtimeVersion = javaVersion;
        this.directory = "java" + javaVersion + suffix;
        this.display = "Java " + javaVersion + (!display.isEmpty() ? " " + display : "");
      }

      @Override
      public String toString() {
        return display;
      }
    }
  }
}