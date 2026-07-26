package me.djmm.java.code_analysis;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class CompilationTest {
    @Test
    void emptyCompilation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Compilation.create(List.of())
        );
    }

    @Test
    void singleFileCompilation() {
        var source = SourceText.of("Test.java", "class Test {}");

        var compilation = Compilation.create(List.of(source));

        assertNoDiagnosticErrors(compilation);
        assertEquals(CompilationPhase.ANALYZED, compilation.phase());
        assertEquals(1, compilation.syntaxTrees().size());

        var tree = compilation.syntaxTrees().get(source.uri());
        assertNotNull(tree);
        assertEquals(com.sun.source.tree.Tree.Kind.COMPILATION_UNIT, tree.getKind());
        assertTrue(compilation.internalErrors().isEmpty());
    }

    @Test
    void multiFileCompilationWithCrossReferences() {
        var sources = List.of(
            SourceText.of("com/example/Base.java",
                "package com.example; public class Base { public int value = 42; }"),
            SourceText.of("com/example/Derived.java",
                "package com.example; public class Derived extends Base { public int doubled() { return value * 2; } }")
        );

        var compilation = Compilation.create(sources);

        assertNoDiagnosticErrors(compilation);
        assertEquals(CompilationPhase.ANALYZED, compilation.phase());
        assertEquals(2, compilation.syntaxTrees().size());
        assertTrue(compilation.internalErrors().isEmpty());
    }

    @Test
    void diagnosticsForErrors() {
        var source = SourceText.of("Bad.java", "class Bad { int x = \"not an int\"; }");

        var compilation = Compilation.create(List.of(source));

        assertFalse(compilation.diagnostics().isEmpty());
        var hasError = compilation.diagnostics().stream()
            .anyMatch(d -> d.getKind() == Diagnostic.Kind.ERROR);
        assertTrue(hasError, "Expected at least one error diagnostic");
    }

    @Test
    void semanticModelAvailable() {
        var source = SourceText.of("Foo.java", "class Foo { int x; }");

        var compilation = Compilation.create(List.of(source));

        var model = compilation.semanticModel();
        assertNotNull(model);
        assertNotNull(model.trees());
        assertNotNull(model.elements());
        assertNotNull(model.types());
    }

    @Test
    void semanticModelResolvesTypes() {
        var source = SourceText.of("TypeCheck.java",
            "class TypeCheck { java.util.List<String> items; }");

        var compilation = Compilation.create(List.of(source));
        assertNoDiagnosticErrors(compilation);

        var model = compilation.semanticModel();
        assertNotNull(model);

        // Verify we can navigate from tree to element
        var tree = compilation.syntaxTrees().get(source.uri());
        var treePath = model.trees().getPath(tree, tree.getTypeDecls().get(0));
        var element = model.trees().getElement(treePath);
        assertNotNull(element);
        assertEquals("TypeCheck", element.getSimpleName().toString());
    }

    @Test
    void compilerOptionsClassPath() {
        var options = CompilerOptions.builder()
            .flag("-Xlint:deprecation")
            .build();

        var source = SourceText.of("WithOptions.java", "class WithOptions {}");
        var compilation = Compilation.create(List.of(source), options);

        assertNoDiagnosticErrors(compilation);
        assertEquals(CompilationPhase.ANALYZED, compilation.phase());
    }

    @Test
    void defaultPackage() {
        var source = SourceText.of("DefaultPkg.java", "class DefaultPkg {}");

        var compilation = Compilation.create(List.of(source));

        assertNoDiagnosticErrors(compilation);
        assertEquals(1, compilation.syntaxTrees().size());
    }

    @Test
    void crossPackageReferences() {
        var sources = List.of(
            SourceText.of("com/a/Api.java",
                "package com.a; public class Api { public static int value() { return 1; } }"),
            SourceText.of("com/b/Client.java",
                "package com.b; import com.a.Api; public class Client { int v = Api.value(); }")
        );

        var compilation = Compilation.create(sources);

        assertNoDiagnosticErrors(compilation);
        assertEquals(CompilationPhase.ANALYZED, compilation.phase());
        assertEquals(2, compilation.syntaxTrees().size());
    }

    private static void assertNoDiagnosticErrors(Compilation compilation) {
        var errors = compilation.diagnostics().stream()
            .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
            .map(d -> d.getMessage(null))
            .toList();
        assertTrue(errors.isEmpty(), "Expected no errors but got: " + errors);
    }
}
