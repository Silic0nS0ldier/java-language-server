package me.djmm.java.code_analysis;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import javax.tools.Diagnostic;
import org.junit.jupiter.api.Test;

public class IncrementalCompilerTest {

  @Test
  void compileEmptyThrows() {
    try (var compiler = IncrementalCompiler.create()) {
      assertThrows(IllegalStateException.class, compiler::compile);
    }
  }

  @Test
  void singleFileCompilation() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
      var result = compiler.compile();

      assertNoDiagnosticErrors(result);
      assertEquals(CompilationPhase.ANALYZED, result.phase());
      assertEquals(1, result.syntaxTrees().size());
    }
  }

  @Test
  void cachedWhenUnchanged() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));

      var result1 = compiler.compile();
      var result2 = compiler.compile();

      assertSame(result1, result2, "Should return cached result when nothing changed");
    }
  }

  @Test
  void recompilesOnSourceUpdate() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
      var result1 = compiler.compile();

      compiler.addOrUpdateSource(
          new SourceText(URI.create("mem:///Foo.java"), "class Foo { int x; }", 1));
      var result2 = compiler.compile();

      assertNotSame(result1, result2, "Should recompile after source change");
      assertNoDiagnosticErrors(result2);
      assertEquals(1, result2.syntaxTrees().size());
    }
  }

  @Test
  void identicalSourceUpdateIsNoOp() {
    try (var compiler = IncrementalCompiler.create()) {
      var source = SourceText.of("Foo.java", "class Foo {}");
      compiler.addOrUpdateSource(source);
      var result1 = compiler.compile();

      // Re-add identical source
      compiler.addOrUpdateSource(source);
      var result2 = compiler.compile();

      assertSame(result1, result2, "Identical source should not trigger recompilation");
    }
  }

  @Test
  void recompilesOnSourceRemoval() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
      compiler.addOrUpdateSource(SourceText.of("Bar.java", "class Bar extends Foo {}"));
      var result1 = compiler.compile();
      assertNoDiagnosticErrors(result1);
      assertEquals(2, result1.syntaxTrees().size());

      compiler.removeSource(URI.create("mem:///Bar.java"));
      var result2 = compiler.compile();

      assertNotSame(result1, result2);
      assertEquals(1, result2.syntaxTrees().size());
    }
  }

  @Test
  void removeNonexistentIsNoOp() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
      var result1 = compiler.compile();

      compiler.removeSource(URI.create("mem:///NonExistent.java"));
      var result2 = compiler.compile();

      assertSame(result1, result2, "Removing non-existent source should not trigger recompilation");
    }
  }

  @Test
  void multipleIncrementalUpdates() {
    try (var compiler = IncrementalCompiler.create()) {
      // v0
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo { int a; }"));
      var r1 = compiler.compile();
      assertNoDiagnosticErrors(r1);

      // v1 — add a field
      compiler.addOrUpdateSource(
          new SourceText(URI.create("mem:///Foo.java"), "class Foo { int a; int b; }", 1));
      var r2 = compiler.compile();
      assertNoDiagnosticErrors(r2);
      assertNotSame(r1, r2);

      // v2 — add a method
      compiler.addOrUpdateSource(
          new SourceText(
              URI.create("mem:///Foo.java"), "class Foo { int a; int b; void m() {} }", 2));
      var r3 = compiler.compile();
      assertNoDiagnosticErrors(r3);
      assertNotSame(r2, r3);
    }
  }

  @Test
  void crossReferenceAfterUpdate() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(
          SourceText.of("com/example/Base.java", "package com.example; public class Base { }"));
      compiler.addOrUpdateSource(
          SourceText.of(
              "com/example/Child.java",
              "package com.example; public class Child extends Base { }"));

      var r1 = compiler.compile();
      assertNoDiagnosticErrors(r1);

      // Add a field to Base — Child should still compile
      compiler.addOrUpdateSource(
          new SourceText(
              URI.create("mem:///com/example/Base.java"),
              "package com.example; public class Base { public int value = 1; }",
              1));

      var r2 = compiler.compile();
      assertNoDiagnosticErrors(r2);
    }
  }

  @Test
  void setSourcesReplacesAll() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
      compiler.addOrUpdateSource(SourceText.of("Bar.java", "class Bar {}"));
      compiler.compile();

      // Replace with entirely new set
      compiler.setSources(List.of(SourceText.of("Baz.java", "class Baz {}")));
      var result = compiler.compile();

      assertNoDiagnosticErrors(result);
      assertEquals(1, result.syntaxTrees().size());
      assertNotNull(result.syntaxTrees().get(URI.create("mem:///Baz.java")));
    }
  }

  @Test
  void setSourcesIdenticalIsNoOp() {
    try (var compiler = IncrementalCompiler.create()) {
      var sources =
          List.of(
              SourceText.of("Foo.java", "class Foo {}"), SourceText.of("Bar.java", "class Bar {}"));
      compiler.setSources(sources);
      var result1 = compiler.compile();

      compiler.setSources(sources);
      var result2 = compiler.compile();

      assertSame(result1, result2, "Identical setSources should not trigger recompilation");
    }
  }

  @Test
  void semanticModelAvailableAfterRecompile() {
    try (var compiler = IncrementalCompiler.create()) {
      compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo { int x; }"));
      var r1 = compiler.compile();
      assertNotNull(r1.semanticModel());
      // Verify first model works before recompile
      assertNotNull(r1.semanticModel().trees());

      compiler.addOrUpdateSource(
          new SourceText(URI.create("mem:///Foo.java"), "class Foo { String y; }", 1));
      var r2 = compiler.compile();

      // Latest compilation's semantic model should work
      assertNotNull(r2.semanticModel());
      assertNotNull(r2.semanticModel().trees());
      assertNotNull(r2.semanticModel().elements());
      assertNotNull(r2.semanticModel().types());
    }
  }

  @Test
  void diagnosticsReportedOnEachCompile() {
    try (var compiler = IncrementalCompiler.create()) {
      // First compile — has an error
      compiler.addOrUpdateSource(SourceText.of("Bad.java", "class Bad { int x = \"oops\"; }"));
      var r1 = compiler.compile();
      assertFalse(r1.diagnostics().isEmpty(), "Should have diagnostics for type error");

      // Fix the error
      compiler.addOrUpdateSource(
          new SourceText(URI.create("mem:///Bad.java"), "class Bad { int x = 42; }", 1));
      var r2 = compiler.compile();
      assertNoDiagnosticErrors(r2);
    }
  }

  @Test
  void closedCompilerThrows() {
    var compiler = IncrementalCompiler.create();
    compiler.close();

    assertThrows(
        IllegalStateException.class,
        () -> compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}")));
    assertThrows(IllegalStateException.class, compiler::compile);
  }

  @Test
  void sourcesViewReflectsState() {
    try (var compiler = IncrementalCompiler.create()) {
      assertTrue(compiler.sources().isEmpty());

      var src = SourceText.of("Foo.java", "class Foo {}");
      compiler.addOrUpdateSource(src);
      assertEquals(1, compiler.sources().size());
      assertEquals(src, compiler.sources().get(src.uri()));

      compiler.removeSource(src.uri());
      assertTrue(compiler.sources().isEmpty());
    }
  }

  private static void assertNoDiagnosticErrors(Compilation compilation) {
    var errors =
        compilation.diagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
    assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
  }
}
