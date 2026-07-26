package me.djmm.java.code_analysis.actions;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import me.djmm.java.code_analysis.Compilation;
import me.djmm.java.code_analysis.SourceText;
import me.djmm.java.code_analysis.symbols.Reference;
import org.junit.jupiter.api.Test;

public class FindReferencesTest {

  @Test
  void findLocalVariableReferences() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int m() {
                    int x = 1;
                    int y = x + x;
                    return y + x;
                }
            }
            """);
    var compilation = Compilation.create(List.of(source));

    // Position of the declaration `x` on line 3 (offset of the 'x' in "int x = 1;")
    var offset = source.content().indexOf("int x") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    // Expect: declaration + 3 uses ("x + x", "+ x")
    assertEquals(4, refs.size(), "Expected 4 references but got: " + refs);
    assertEquals(1, refs.stream().filter(r -> r.kind() == Reference.Kind.DECLARATION).count());
    assertEquals(3, refs.stream().filter(r -> r.kind() == Reference.Kind.USE).count());
  }

  @Test
  void findFieldReferences() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int value = 0;
                int get() { return value; }
                void set(int v) { this.value = v; }
            }
            """);
    var compilation = Compilation.create(List.of(source));

    var offset = source.content().indexOf("int value") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    // Expect: declaration + 2 uses (return value; this.value)
    assertEquals(3, refs.size(), "Expected 3 references but got: " + refs);
    assertEquals(1, refs.stream().filter(r -> r.kind() == Reference.Kind.DECLARATION).count());
  }

  @Test
  void findMethodReferences() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int calc() { return 1; }
                int m() { return calc() + calc(); }
            }
            """);
    var compilation = Compilation.create(List.of(source));

    var offset = source.content().indexOf("int calc") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    assertEquals(3, refs.size(), "Expected 3 references (declaration + 2 uses) but got: " + refs);
    assertEquals(1, refs.stream().filter(r -> r.kind() == Reference.Kind.DECLARATION).count());
    assertEquals(2, refs.stream().filter(r -> r.kind() == Reference.Kind.USE).count());
  }

  @Test
  void findTypeReferencesAcrossFiles() {
    var base =
        SourceText.of(
            "com/example/Base.java",
            """
            package com.example;
            public class Base {}
            """);
    var user1 =
        SourceText.of(
            "com/example/UserA.java",
            """
            package com.example;
            public class UserA {
                Base b = new Base();
            }
            """);
    var user2 =
        SourceText.of(
            "com/example/UserB.java",
            """
            package com.example;
            public class UserB extends Base {
                Base other;
            }
            """);
    var compilation = Compilation.create(List.of(base, user1, user2));

    // Point at the class name `Base` in the declaration
    var offset = base.content().indexOf("class Base") + "class ".length();
    var refs = FindReferences.atPosition(compilation, base.uri(), offset);

    // Expect: declaration in Base.java, and uses in UserA (field type, new Base()),
    // UserB (extends, field type). That's at least 5 references.
    assertTrue(
        refs.size() >= 5, "Expected at least 5 references but got " + refs.size() + ": " + refs);

    // At least one reference in each user file
    assertTrue(
        refs.stream().anyMatch(r -> r.uri().equals(user1.uri())), "Expected reference in UserA");
    assertTrue(
        refs.stream().anyMatch(r -> r.uri().equals(user2.uri())), "Expected reference in UserB");
  }

  @Test
  void referenceRangesPointAtIdentifier() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int x = 1;
                int y = x + 2;
            }
            """);
    var compilation = Compilation.create(List.of(source));
    var offset = source.content().indexOf("int x") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    // Every range should span exactly one character (the 'x')
    for (var ref : refs) {
      var length = ref.range().endOffset() - ref.range().startOffset();
      assertEquals(1, length, "Reference range should be exactly the identifier length: " + ref);
      assertEquals(
          "x",
          source.content().substring(ref.range().startOffset(), ref.range().endOffset()),
          "Range should point at 'x'");
    }
  }

  @Test
  void noSymbolAtOffsetReturnsEmpty() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int x = 1;
            }
            """);
    var compilation = Compilation.create(List.of(source));

    // Offset inside whitespace
    var offset = 0;
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);
    assertTrue(refs.isEmpty(), "Expected no references but got: " + refs);
  }

  @Test
  void missingUriThrows() {
    var source = SourceText.of("Foo.java", "class Foo {}");
    var compilation = Compilation.create(List.of(source));

    assertThrows(
        FindReferences.NoSuchSourceException.class,
        () -> FindReferences.atPosition(compilation, URI.create("mem:///Missing.java"), 0));
  }

  @Test
  void findConstructorReferences() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                Foo() {}
                static Foo make() { return new Foo(); }
            }
            """);
    var compilation = Compilation.create(List.of(source));

    // Point at the constructor declaration
    var offset = source.content().indexOf("Foo() {}");
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    // Declaration + one `new Foo()` use
    assertTrue(refs.size() >= 2, "Expected constructor declaration + use, got: " + refs);
  }

  @Test
  void findParameterReferences() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int add(int a, int b) { return a + b + a; }
            }
            """);
    var compilation = Compilation.create(List.of(source));

    // Anchor uniquely on `int a,` — the parameter, not the return type or method name
    var offset = source.content().indexOf("int a,") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);

    // Declaration + 2 uses of `a`
    assertEquals(3, refs.size(), "Expected 3 references for parameter a, got: " + refs);
  }

  @Test
  void forElementDirect() {
    var source =
        SourceText.of(
            "Foo.java",
            """
            class Foo {
                int x = 1;
                int y = x;
            }
            """);
    var compilation = Compilation.create(List.of(source));

    var model = compilation.semanticModel();
    assertNotNull(model);

    // Find the VariableTree for `x` and get its element
    var root = compilation.syntaxTrees().get(source.uri());
    var elements = model.elements();
    var typeFoo = elements.getTypeElement("Foo");
    assertNotNull(typeFoo);

    var xField =
        typeFoo.getEnclosedElements().stream()
            .filter(e -> e.getSimpleName().contentEquals("x"))
            .findFirst()
            .orElseThrow();

    var refs = FindReferences.forElement(compilation, xField);
    assertEquals(2, refs.size(), "Expected 2 references (declaration + use), got: " + refs);
  }

  @Test
  void lineAndColumnAreOneBased() {
    var source = SourceText.of("Foo.java", "class Foo { int x = 1; int y = x; }");
    var compilation = Compilation.create(List.of(source));

    var offset = source.content().indexOf("int x") + "int ".length();
    var refs = FindReferences.atPosition(compilation, source.uri(), offset);
    assertFalse(refs.isEmpty());

    for (var ref : refs) {
      assertTrue(
          ref.range().startLine() >= 1, "Line should be 1-based, got: " + ref.range().startLine());
      assertTrue(
          ref.range().startColumn() >= 1,
          "Column should be 1-based, got: " + ref.range().startColumn());
    }
  }
}
