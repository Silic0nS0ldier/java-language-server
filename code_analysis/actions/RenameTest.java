package me.djmm.java.code_analysis.actions;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import me.djmm.java.code_analysis.Compilation;
import me.djmm.java.code_analysis.SourceText;
import me.djmm.java.code_analysis.text.TextEdit;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class RenameTest {

    @Test
    void renameLocalVariable() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                int m() {
                    int x = 1;
                    int y = x + x;
                    return y + x;
                }
            }
            """);
        var compilation = Compilation.create(List.of(source));

        var offset = source.content().indexOf("int x") + "int ".length();
        var edits = Rename.atPosition(compilation, source.uri(), offset, "count");

        assertEquals(4, edits.size(), "Declaration + 3 uses should be renamed");
        var updated = applyEdits(source, edits);
        assertTrue(updated.contains("int count = 1;"));
        assertTrue(updated.contains("int y = count + count;"));
        assertTrue(updated.contains("return y + count;"));
    }

    @Test
    void renameField() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                int value = 0;
                int get() { return value; }
                void set(int v) { this.value = v; }
            }
            """);
        var compilation = Compilation.create(List.of(source));

        var offset = source.content().indexOf("int value") + "int ".length();
        var edits = Rename.atPosition(compilation, source.uri(), offset, "amount");

        assertEquals(3, edits.size());
        var updated = applyEdits(source, edits);
        assertTrue(updated.contains("int amount = 0;"));
        assertTrue(updated.contains("return amount;"));
        assertTrue(updated.contains("this.amount = v;"));
        assertFalse(updated.contains("value"),
            "No remaining occurrences of the old name, got:\n" + updated);
    }

    @Test
    void renameMethod() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                int calc() { return 1; }
                int m() { return calc() + calc(); }
            }
            """);
        var compilation = Compilation.create(List.of(source));

        var offset = source.content().indexOf("int calc") + "int ".length();
        var edits = Rename.atPosition(compilation, source.uri(), offset, "compute");

        assertEquals(3, edits.size());
        var updated = applyEdits(source, edits);
        assertTrue(updated.contains("int compute()"));
        assertTrue(updated.contains("return compute() + compute();"));
        assertFalse(updated.contains("calc"));
    }

    @Test
    void renameClassAlsoRenamesConstructors() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                Foo() {}
                Foo(int x) {}
                static Foo make() { return new Foo(); }
            }
            """);
        var compilation = Compilation.create(List.of(source));

        var offset = source.content().indexOf("class Foo") + "class ".length();
        var edits = Rename.atPosition(compilation, source.uri(), offset, "Bar");

        var updated = applyEdits(source, edits);
        assertTrue(updated.contains("class Bar"),
            "Class declaration should be renamed:\n" + updated);
        assertTrue(updated.contains("Bar() {}"),
            "Constructor declarations should be renamed:\n" + updated);
        assertTrue(updated.contains("Bar(int x) {}"),
            "Overloaded constructor declarations should be renamed:\n" + updated);
        assertTrue(updated.contains("static Bar make()"),
            "Return type usage should be renamed:\n" + updated);
        assertTrue(updated.contains("new Bar()"),
            "Constructor invocation should be renamed:\n" + updated);
        assertFalse(updated.contains("Foo"),
            "No remaining occurrences of the old name, got:\n" + updated);
    }

    @Test
    void renameConstructorAlsoRenamesClass() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                Foo() {}
            }
            """);
        var compilation = Compilation.create(List.of(source));

        var offset = source.content().indexOf("Foo() {}");
        var edits = Rename.atPosition(compilation, source.uri(), offset, "Bar");

        var updated = applyEdits(source, edits);
        assertTrue(updated.contains("class Bar"),
            "Class declaration should be renamed:\n" + updated);
        assertTrue(updated.contains("Bar() {}"),
            "Constructor declaration should be renamed:\n" + updated);
    }

    @Test
    void renameAcrossFiles() {
        var base = SourceText.of("com/example/Base.java", """
            package com.example;
            public class Base {}
            """);
        var user = SourceText.of("com/example/User.java", """
            package com.example;
            public class User {
                Base b = new Base();
            }
            """);
        var compilation = Compilation.create(List.of(base, user));

        var offset = base.content().indexOf("class Base") + "class ".length();
        var edits = Rename.atPosition(compilation, base.uri(), offset, "Root");

        // Group edits by file and apply
        var updatedBase = applyEdits(base, filterByUri(edits, base.uri()));
        var updatedUser = applyEdits(user, filterByUri(edits, user.uri()));

        assertTrue(updatedBase.contains("class Root"));
        assertTrue(updatedUser.contains("Root b = new Root();"));
        assertFalse(updatedBase.contains("Base"));
        assertFalse(updatedUser.contains("Base"));
    }

    @Test
    void rejectsEmptyName() {
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo { int x = 0; }")
        ));
        var ex = assertThrows(Rename.InvalidIdentifierException.class,
            () -> Rename.atPosition(compilation, URI.create("mem:///Foo.java"), 0, ""));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    void rejectsInvalidIdentifierStartCharacter() {
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo {}")
        ));
        assertThrows(Rename.InvalidIdentifierException.class,
            () -> Rename.atPosition(compilation, URI.create("mem:///Foo.java"), 0, "1abc"));
    }

    @Test
    void rejectsInvalidIdentifierBodyCharacter() {
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo {}")
        ));
        assertThrows(Rename.InvalidIdentifierException.class,
            () -> Rename.atPosition(compilation, URI.create("mem:///Foo.java"), 0, "foo-bar"));
    }

    @Test
    void rejectsReservedKeywords() {
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo {}")
        ));
        for (var reserved : List.of("class", "int", "void", "true", "false", "null", "_")) {
            assertThrows(Rename.InvalidIdentifierException.class,
                () -> Rename.atPosition(compilation, URI.create("mem:///Foo.java"), 0, reserved),
                "Should reject reserved word: " + reserved);
        }
    }

    @Test
    void noSymbolAtOffsetReturnsEmpty() {
        var source = SourceText.of("Foo.java", """
            class Foo {
                int x = 1;
            }
            """);
        var compilation = Compilation.create(List.of(source));

        // Offset points at whitespace
        var edits = Rename.atPosition(compilation, source.uri(), 0, "newName");
        assertTrue(edits.isEmpty());
    }

    @Test
    void missingUriThrows() {
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo {}")
        ));
        assertThrows(FindReferences.NoSuchSourceException.class,
            () -> Rename.atPosition(compilation, URI.create("mem:///Missing.java"), 0, "x"));
    }

    @Test
    void validatesNameBeforeResolvingElement() {
        // Even when the URI is missing, an invalid identifier should be reported first.
        var compilation = Compilation.create(List.of(
            SourceText.of("Foo.java", "class Foo {}")
        ));
        assertThrows(Rename.InvalidIdentifierException.class,
            () -> Rename.atPosition(compilation, URI.create("mem:///Missing.java"), 0, ""));
    }

    @Test
    void editsProduceIdenticalOutputWhenApplied() {
        // Verify the range/replacement semantics: applying edits to the source produces
        // exactly the expected output with no drift.
        var source = SourceText.of("Foo.java", """
            class Foo {
                int x;
                int y = x + 1;
                int z = x + 2;
            }
            """);
        var compilation = Compilation.create(List.of(source));
        var offset = source.content().indexOf("int x") + "int ".length();
        var edits = Rename.atPosition(compilation, source.uri(), offset, "renamed");

        var updated = applyEdits(source, edits);
        var expected = """
            class Foo {
                int renamed;
                int y = renamed + 1;
                int z = renamed + 2;
            }
            """;
        assertEquals(expected, updated);
    }

    // --- helpers ---

    /**
     * Apply a list of edits to a single source. Edits are sorted by descending
     * start offset so earlier edits do not shift the offsets of later ones.
     */
    private static String applyEdits(SourceText source, List<TextEdit> edits) {
        var sb = new StringBuilder(source.content());
        var sorted = edits.stream()
            .filter(e -> e.uri().equals(source.uri()))
            .sorted((a, b) -> Integer.compare(b.range().startOffset(), a.range().startOffset()))
            .toList();
        // Detect overlapping ranges — would produce garbage otherwise.
        for (var i = 0; i < sorted.size() - 1; i++) {
            var later = sorted.get(i).range();
            var earlier = sorted.get(i + 1).range();
            if (earlier.endOffset() > later.startOffset()) {
                fail("Overlapping edits: " + earlier + " and " + later);
            }
        }
        for (var edit : sorted) {
            sb.replace(edit.range().startOffset(), edit.range().endOffset(), edit.replacement());
        }
        return sb.toString();
    }

    private static List<TextEdit> filterByUri(List<TextEdit> edits, URI uri) {
        return edits.stream().filter(e -> e.uri().equals(uri)).collect(Collectors.toList());
    }
}
