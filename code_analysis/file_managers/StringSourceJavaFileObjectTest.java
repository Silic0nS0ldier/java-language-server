package me.djmm.java.code_analysis.file_managers;

import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class StringSourceJavaFileObjectTest {
    @Test
    void basic() {
        var uri = URI.create("mem:///test.java");
        var source = "class Test {}";
        var fileObject = new StringSourceJavaFileObject(uri, source, 0);
        assertEquals(source, fileObject.source);
        assertEquals(0, fileObject.getLastModified());
        assertEquals(".java", fileObject.getKind().extension);
    }

    @Test
    void packageNameExtracted() {
        var uri = URI.create("mem:///com/example/Foo.java");
        var fileObject = new StringSourceJavaFileObject(uri, "package com.example; class Foo {}", 0);
        assertEquals("com.example", fileObject.packageName());
    }

    @Test
    void binaryNameWithPackage() {
        var uri = URI.create("mem:///com/example/Foo.java");
        var fileObject = new StringSourceJavaFileObject(uri, "package com.example; class Foo {}", 0);
        assertEquals("com.example.Foo", fileObject.binaryName());
    }

    @Test
    void binaryNameDefaultPackage() {
        var uri = URI.create("mem:///Test.java");
        var fileObject = new StringSourceJavaFileObject(uri, "class Test {}", 0);
        assertEquals("Test", fileObject.binaryName());
    }
}
