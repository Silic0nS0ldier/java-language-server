package me.djmm.java.code_analysis.file_managers;

import com.google.common.collect.ImmutableMap;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import static javax.tools.ToolProvider.getSystemJavaCompiler;

// Based off https://github.com/google/compile-testing/blob/8259709a0f5995c7cc89f28425ca1a055bc5b24d/src/main/java/com/google/testing/compile/InMemoryJavaFileManager.java#L54
// TODO While this needs to be public to allow in-memory compilation, the implementaion should be package-private.
// This reuses the builtin `JavaFileManager` (via `ForwardingJavaFileManager`) to avoid needing to implement all methods (e.g. `getClassLoader`).
public final class InMemoryJavaFileManager extends ForwardingJavaFileManager<StandardJavaFileManager> implements JavaFileManager {
    private static final JavaCompiler javaCompiler = getSystemJavaCompiler();
    private static final String memScheme = "mem";
    
    private final ImmutableMap<URI, JavaFileObject> inputs;
    private final HashMap<URI, JavaFileObject> outputs = new HashMap<>();

    public InMemoryJavaFileManager(List<? extends JavaFileObject> inputs) {
        super(javaCompiler.getStandardFileManager(null, null, null));
        var inputsBuilder = ImmutableMap.<URI, JavaFileObject>builder();
        for (var source : inputs) {
            var uri = source.toUri();
            var scheme = uri.getScheme();
            if (!scheme.equals(memScheme)) {
                throw new IllegalArgumentException(String.format("URI scheme must be '%s' but got '%s' for URI '%s'", memScheme, scheme, uri.toString()));
            }
            inputsBuilder.put(uri, source);
        }
        this.inputs = inputsBuilder.build();
    }

    private static URI uriForFileObject(Location location, String packageName, String relativeName) {
        try {
            StringBuilder uri = new StringBuilder("mem:///").append(location.getName()).append('/');
            if (!packageName.isEmpty()) {
                uri.append(packageName.replace('.', '/')).append('/');
            }
            uri.append(relativeName);
            return URI.create(uri.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static @Nullable URI uriForJavaFileObject(Location location, String className, JavaFileObject.Kind kind) {
        try {
            return URI.create("mem:///" + location.getName() + '/' + className.replace('.', '/') + kind.extension);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public @Nullable FileObject getFileForInput(JavaFileManager.Location location, String packageName, String relativeName) throws IOException {
        URI uri = uriForFileObject(location, packageName, relativeName);
        if (location.isOutputLocation()) {
            return this.outputs.get(uri);
        }

        var maybe = this.inputs.get(uri);
        if (maybe != null) {
            return maybe;
        }
        
        // Fallback (e.g. for builtin modules)
        return super.getFileForInput(location, packageName, relativeName);
    }

    @Override
    public FileObject getFileForOutput(JavaFileManager.Location location, String packageName, String relativeName, FileObject sibling) {
        URI uri = uriForFileObject(location, packageName, relativeName);
        var output = this.outputs.get(uri);
        if (output == null) {
            throw new IllegalStateException("Output file not found for URI: " + uri);
        }
        return output;
    }

    @Override
    public @Nullable JavaFileObject getJavaFileForInput(JavaFileManager.Location location, String className, JavaFileObject.Kind kind) throws IOException {
        var uri = uriForJavaFileObject(location, className, kind);
        if (location.isOutputLocation()) {
            return this.outputs.get(uri);
        }

        var maybe = this.inputs.get(uri);
        if (maybe != null) {
            return maybe;
        }

        return super.getJavaFileForInput(location, className, kind);
    }

    @Override
    public JavaFileObject getJavaFileForOutput(JavaFileManager.Location location, String className, JavaFileObject.Kind kind, FileObject sibling) {
        URI uri = uriForJavaFileObject(location, className, kind);
        var output = this.outputs.get(uri);
        if (output == null) {
            throw new IllegalStateException("Output file not found for URI: " + uri);
        }
        return output;
    }

    // Implementation this is based off uses a less strict check, which we not need to worry about
    // @Override
    // public boolean isSameFile(FileObject a, FileObject b) {
    //     throw new UnsupportedOperationException();
    // }
}
