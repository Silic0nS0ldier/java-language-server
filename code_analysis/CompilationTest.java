package me.djmm.java.code_analysis;

import java.net.URI;
import java.util.ArrayList;
import javax.tools.JavaFileObject;
import me.djmm.java.code_analysis.file_managers.InMemoryJavaFileManager;
import me.djmm.java.code_analysis.file_managers.StringSourceJavaFileObject;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

public class CompilationTest {
    @Test
    void emptyCompilation() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Compilation.perform(new ArrayList<>(), new InMemoryJavaFileManager(new ArrayList<>()))
        );
    }

    @Test
    void singleFileCompilation() {
        var files = new ArrayList<StringSourceJavaFileObject>();
        var fileUri = URI.create("mem://test.java");
        files.add(new StringSourceJavaFileObject(fileUri, "class Test {}", 0));
        var fileManager = new InMemoryJavaFileManager(files);
        
        var compilation = Compilation.perform(files, fileManager);

        assertEquals(0, compilation.diagnostics.size());
        assertEquals(1, compilation.sources.size());
        var tree = compilation.sources.get(fileUri);
        assertEquals(
            """
            \nclass Test {
                \n\
                Test() {
                    super();
                }
            }\
            """,
            tree.toString()
        );
        assertEquals(com.sun.source.tree.Tree.Kind.COMPILATION_UNIT, tree.getKind());
        assertNull(compilation.errors.analyze());
        assertNull(compilation.errors.parse());
        assertNull(compilation.errors.fallback());
    }
}
