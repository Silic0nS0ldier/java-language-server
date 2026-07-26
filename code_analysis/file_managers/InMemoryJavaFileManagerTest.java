package me.djmm.java.code_analysis.file_managers;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.junit.jupiter.api.Test;

public class InMemoryJavaFileManagerTest {
  @Test
  void empty() {
    InMemoryJavaFileManager.create(new ArrayList<>());
  }

  @Test
  void populated() {
    var inputs = new ArrayList<StringSourceJavaFileObject>();
    inputs.add(new StringSourceJavaFileObject(URI.create("mem:///Test.java"), "class Test {}", 0));
    InMemoryJavaFileManager.create(inputs);
  }

  @Test
  void listSourcePathReturnsInMemorySources() throws IOException {
    var inputs =
        List.<JavaFileObject>of(
            new StringSourceJavaFileObject(
                URI.create("mem:///com/example/Foo.java"), "package com.example; class Foo {}", 0),
            new StringSourceJavaFileObject(
                URI.create("mem:///com/example/Bar.java"), "package com.example; class Bar {}", 0),
            new StringSourceJavaFileObject(
                URI.create("mem:///com/other/Baz.java"), "package com.other; class Baz {}", 0));
    var fm = InMemoryJavaFileManager.create(inputs);

    var result = new ArrayList<JavaFileObject>();
    for (var obj :
        fm.list(
            StandardLocation.SOURCE_PATH,
            "com.example",
            Set.of(JavaFileObject.Kind.SOURCE),
            false)) {
      result.add(obj);
    }

    assertEquals(2, result.size());
  }

  @Test
  void inferBinaryNameForInMemorySource() {
    var inputs =
        List.<JavaFileObject>of(
            new StringSourceJavaFileObject(
                URI.create("mem:///com/example/Foo.java"), "package com.example; class Foo {}", 0));
    var fm = InMemoryJavaFileManager.create(inputs);

    assertEquals(
        "com.example.Foo", fm.inferBinaryName(StandardLocation.SOURCE_PATH, inputs.get(0)));
  }

  @Test
  void hasSourcePathLocation() {
    var inputs =
        List.<JavaFileObject>of(
            new StringSourceJavaFileObject(URI.create("mem:///Foo.java"), "class Foo {}", 0));
    var fm = InMemoryJavaFileManager.create(inputs);

    assertTrue(fm.hasLocation(StandardLocation.SOURCE_PATH));
  }

  @Test
  void rejectsNonMemScheme() {
    var inputs =
        List.<JavaFileObject>of(
            new StringSourceJavaFileObject(URI.create("file:///Foo.java"), "class Foo {}", 0));
    assertThrows(IllegalArgumentException.class, () -> InMemoryJavaFileManager.create(inputs));
  }
}
