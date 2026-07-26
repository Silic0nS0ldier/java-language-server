# Java Code Analysis

An abstraction over the Java Compiler API focused on analysis and refactoring tasks.

## Quick Start

```java
import me.djmm.java.code_analysis.*;

// Define sources
var sources = List.of(
    SourceText.of("com/example/Greeter.java", """
        package com.example;
        public class Greeter {
            public String greet(String name) {
                return "Hello, " + name;
            }
        }
    """),
    SourceText.of("com/example/Main.java", """
        package com.example;
        public class Main {
            public static void main(String[] args) {
                System.out.println(new Greeter().greet("world"));
            }
        }
    """)
);

// Compile
var compilation = Compilation.create(sources);

// Check diagnostics
for (var diag : compilation.diagnostics()) {
    System.out.println(diag.getMessage(null));
}

// Access syntax trees
for (var entry : compilation.syntaxTrees().entrySet()) {
    System.out.println(entry.getKey() + " -> " + entry.getValue().getKind());
}

// Use the semantic model for type-level queries
var model = compilation.semanticModel();
var trees = model.trees();
var elements = model.elements();
```

## Key Types

| Type | Purpose |
|---|---|
| `SourceText` | Immutable representation of a source file (URI + content + version) |
| `Compilation` | Immutable result of compiling sources — syntax trees, diagnostics, semantic model |
| `IncrementalCompiler` | Stateful compiler that reuses javac state across compilations for fast re-compilation |
| `CompilerOptions` | Configuration for compilation (classpath, compiler flags) |
| `SemanticModel` | Access to javac's `Trees`, `Elements`, and `Types` for semantic queries |
| `CompilationPhase` | Tracks compilation progress (`PARSED`, `ANALYZED`) |

## Incremental Compilation

For interactive scenarios (language servers, live analysis) where the same set of
sources is compiled repeatedly with small changes, use `IncrementalCompiler`. It
reuses javac's internal `Context` — JDK symbol tables, module system state, and
type caches — so subsequent compilations skip cold-start overhead.

```java
import me.djmm.java.code_analysis.*;
import java.net.URI;

try (var compiler = IncrementalCompiler.create()) {
    // Seed the compiler with initial sources
    compiler.addOrUpdateSource(SourceText.of("com/example/Foo.java",
        "package com.example; public class Foo { public int value() { return 1; } }"));
    compiler.addOrUpdateSource(SourceText.of("com/example/Bar.java",
        "package com.example; public class Bar { int x = new Foo().value(); }"));

    // First compilation — pays the cold-start cost once
    var first = compiler.compile();

    // Edit Foo.java — bump the version and update
    compiler.addOrUpdateSource(new SourceText(
        URI.create("mem:///com/example/Foo.java"),
        "package com.example; public class Foo { public int value() { return 42; } }",
        1
    ));

    // Re-compile — reuses cached javac state
    var second = compiler.compile();
    assert second != first;                     // fresh result
    assert second.diagnostics().isEmpty();      // still valid

    // No changes since last compile — returns the cached result (same identity)
    var third = compiler.compile();
    assert third == second;

    // Remove a source
    compiler.removeSource(URI.create("mem:///com/example/Bar.java"));
    var fourth = compiler.compile();

    // Replace the entire source set atomically
    compiler.setSources(List.of(
        SourceText.of("com/example/NewFile.java", "package com.example; class NewFile {}")
    ));
    var fifth = compiler.compile();
}
```

### Change tracking

`IncrementalCompiler` uses a generation counter to determine when re-compilation
is needed. Mutation methods only bump the generation when something actually
changes:

- `addOrUpdateSource(src)` — no-op if `src` equals the existing entry (same URI,
  content, and version). Bumps the generation otherwise.
- `removeSource(uri)` — no-op if the URI is not tracked.
- `setSources(collection)` — no-op if the resulting map equals the current one.

`compile()` returns the cached `Compilation` (same object identity) when the
generation has not changed since the last call.

### Lifecycle and semantic model validity

Each successful `compile()` call supersedes the previous compilation. The
returned `Compilation` object itself remains valid — its syntax trees,
diagnostics, and other immutable state can be read at any time. However, the
`SemanticModel` from a superseded `Compilation` must not be used, because it
holds references to javac state that has been reset:

```java
var r1 = compiler.compile();
var model1 = r1.semanticModel();     // valid

var r2 = compiler.compile();          // supersedes r1
var model2 = r2.semanticModel();     // valid

// model1.trees()  ← DO NOT — its underlying task has been torn down
r1.diagnostics();                     // still fine — immutable snapshot
```

Close the compiler when finished to release resources deterministically. The
try-with-resources pattern shown above is the recommended usage.

### Thread safety

`IncrementalCompiler` is **not** thread-safe. All method calls on a single
instance must be serialised by the caller. Concurrent compilations require
separate `IncrementalCompiler` instances.

## Supported Java versions

The library targets `--release 21` bytecode and is validated against the
following JDK runtimes:

| Runtime           | Status  |
|-------------------|---------|
| JDK 21 (LTS)      | Tested  |
| JDK 25 (LTS)      | Tested  |

Because `code_analysis` calls internal `com.sun.tools.javac.*` APIs, behaviour
can shift between JDK feature releases (e.g. `Log.clear()` changed from
package-private to `public` in JDK 25). Every JUnit 5 test is therefore
executed once per supported runtime.

### Running tests across all supported JDKs

```bash
# Runs every test on every supported JDK in a single invocation:
bazel test //code_analysis:tests
```

The Bazel test suite `//code_analysis:tests` fans out via a Starlark transition
(see [//:build_defs/multi_jdk_test.bzl](../build_defs/multi_jdk_test.bzl)) into
one child target per JDK — for example:

- `//code_analysis:tests_jdk21` — the suite executed on JDK 21
- `//code_analysis:tests_jdk25` — the suite executed on JDK 25

Failures surface with the JDK-specific target name in the test log so it is
immediately clear which runtime regressed.

### Running against a single JDK

```bash
bazel test //code_analysis:tests_jdk21   # or :tests_jdk25
```

You can also change the *ambient* runtime used by every Java target in the
repo — useful when experimenting outside the test suite:

```bash
bazel test //code_analysis:tests --config=jdk25
```

### Adding another JDK

1. Ensure the JDK is available through `rules_java`'s prebuilt remote
   toolchains (currently 21 and 25 out of the box) or register a custom
   toolchain in `MODULE.bazel`.
2. Add the runtime version string (e.g. `"remotejdk_23"`) to
   `java_runtime_versions` in [BUILD.bazel](BUILD.bazel).
3. Optionally add a nickname mapping in
   [`multi_jdk_test.bzl`](../build_defs/multi_jdk_test.bzl) for a friendlier
   suffix (`jdk23` instead of the auto-generated `jdk23`).
