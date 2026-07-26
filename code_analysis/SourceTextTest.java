package me.djmm.java.code_analysis;

import java.net.URI;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class SourceTextTest {
    @Test
    void ofCreatesMemUri() {
        var source = SourceText.of("com/example/Foo.java", "package com.example; class Foo {}");
        assertEquals(URI.create("mem:///com/example/Foo.java"), source.uri());
        assertEquals(0, source.version());
    }

    @Test
    void packageNameExtracted() {
        var source = SourceText.of("com/example/Foo.java", "package com.example;\nclass Foo {}");
        assertEquals("com.example", source.packageName());
    }

    @Test
    void defaultPackage() {
        var source = SourceText.of("Foo.java", "class Foo {}");
        assertEquals("", source.packageName());
    }

    @Test
    void simpleClassName() {
        var source = SourceText.of("com/example/Foo.java", "package com.example; class Foo {}");
        assertEquals("Foo", source.simpleClassName());
    }

    @Test
    void binaryName() {
        var source = SourceText.of("com/example/Foo.java", "package com.example; class Foo {}");
        assertEquals("com.example.Foo", source.binaryName());
    }

    @Test
    void binaryNameDefaultPackage() {
        var source = SourceText.of("Foo.java", "class Foo {}");
        assertEquals("Foo", source.binaryName());
    }

    @Test
    void nullUriRejected() {
        assertThrows(NullPointerException.class, () -> new SourceText(null, "", 0));
    }

    @Test
    void nullContentRejected() {
        assertThrows(NullPointerException.class, () -> new SourceText(URI.create("mem:///x"), null, 0));
    }

    @Test
    void negativeVersionRejected() {
        assertThrows(IllegalArgumentException.class, () -> new SourceText(URI.create("mem:///x"), "", -1));
    }

    @Test
    void versionPreserved() {
        var source = new SourceText(URI.create("mem:///Foo.java"), "class Foo {}", 42);
        assertEquals(42, source.version());
    }

    @Test
    void ofStripsLeadingSlash() {
        var source = SourceText.of("/com/example/Foo.java", "class Foo {}");
        assertEquals(URI.create("mem:///com/example/Foo.java"), source.uri());
    }
}
