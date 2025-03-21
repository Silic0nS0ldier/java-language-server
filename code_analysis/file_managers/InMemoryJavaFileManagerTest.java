package me.djmm.java.code_analysis.file_managers;

import java.net.URI;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class InMemoryJavaFileManagerTest {
    @Test
    void empty() {
        // Note to self: First run triggered a SIGBUS error. Could not reproduce.
        // This could be a bug, or a hardware fault (WSL has been crashing a lot lately).
        new InMemoryJavaFileManager(new ArrayList<>());
    }

    @Test
    void populated() {
        var inputs = new ArrayList<StringSourceJavaFileObject>();
        inputs.add(new StringSourceJavaFileObject(URI.create("mem://test.java"), "class Test {}", 0));
        new InMemoryJavaFileManager(inputs);
    }
}
