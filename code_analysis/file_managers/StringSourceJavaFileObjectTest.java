package me.djmm.java.code_analysis.file_managers;

import java.net.URI;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class StringSourceJavaFileObjectTest {
    @Test
    void basic() {
        var uri = URI.create("mem://test");
        var source = "class Test {}";
        var fileObject = new StringSourceJavaFileObject(uri, source, 0);
        assertEquals("", fileObject.getName());
        assertEquals(source, fileObject.source);
        assertEquals(0, fileObject.getLastModified());
        assertEquals(".java", fileObject.getKind().extension);
    }
}
