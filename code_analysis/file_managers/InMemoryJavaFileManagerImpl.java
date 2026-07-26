package me.djmm.java.code_analysis.file_managers;

import static javax.tools.ToolProvider.getSystemJavaCompiler;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSetMultimap;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;

// Based off
// https://github.com/google/compile-testing/blob/8259709a0f5995c7cc89f28425ca1a055bc5b24d/src/main/java/com/google/testing/compile/InMemoryJavaFileManager.java#L54
// Package-private implementation; construct via {@link InMemoryJavaFileManager#create(List)}.
// This reuses the builtin `JavaFileManager` (via `ForwardingJavaFileManager`) to avoid needing to
// implement all methods (e.g. `getClassLoader`).
final class InMemoryJavaFileManagerImpl extends ForwardingJavaFileManager<StandardJavaFileManager>
    implements JavaFileManager {
  private static final JavaCompiler javaCompiler = getSystemJavaCompiler();
  private static final String memScheme = "mem";

  private final ImmutableMap<URI, JavaFileObject> inputs;

  /** Index from package name to source file objects in that package. */
  private final ImmutableSetMultimap<String, JavaFileObject> packageIndex;

  /** Index from binary name to source file object. */
  private final ImmutableMap<String, StringSourceJavaFileObject> binaryNameIndex;

  /** Reverse lookup: file object → binary name (for inferBinaryName). */
  private final ImmutableMap<JavaFileObject, String> reverseBinaryNameIndex;

  private final HashMap<URI, JavaFileObject> outputs = new HashMap<>();

  InMemoryJavaFileManagerImpl(List<? extends JavaFileObject> inputs) {
    super(javaCompiler.getStandardFileManager(null, null, null));
    var inputsBuilder = ImmutableMap.<URI, JavaFileObject>builder();
    var packageIndexBuilder = ImmutableSetMultimap.<String, JavaFileObject>builder();
    var binaryNameBuilder = ImmutableMap.<String, StringSourceJavaFileObject>builder();
    var reverseBinaryNameBuilder = ImmutableMap.<JavaFileObject, String>builder();
    for (var source : inputs) {
      var uri = source.toUri();
      var scheme = uri.getScheme();
      if (!scheme.equals(memScheme)) {
        throw new IllegalArgumentException(
            String.format(
                "URI scheme must be '%s' but got '%s' for URI '%s'",
                memScheme, scheme, uri.toString()));
      }
      inputsBuilder.put(uri, source);
      if (source instanceof StringSourceJavaFileObject stringSource) {
        var pkg = stringSource.packageName();
        var binaryName = stringSource.binaryName();
        packageIndexBuilder.put(pkg, source);
        binaryNameBuilder.put(binaryName, stringSource);
        reverseBinaryNameBuilder.put(source, binaryName);
      }
    }
    this.inputs = inputsBuilder.build();
    this.packageIndex = packageIndexBuilder.build();
    this.binaryNameIndex = binaryNameBuilder.build();
    this.reverseBinaryNameIndex = reverseBinaryNameBuilder.build();
  }

  private static @Nullable URI uriForFileObject(
      Location location, String packageName, String relativeName) {
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

  private static @Nullable URI uriForJavaFileObject(
      Location location, String className, JavaFileObject.Kind kind) {
    try {
      return URI.create(
          "mem:///" + location.getName() + '/' + className.replace('.', '/') + kind.extension);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  @Override
  public @Nullable FileObject getFileForInput(
      JavaFileManager.Location location, String packageName, String relativeName)
      throws IOException {
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
  public FileObject getFileForOutput(
      JavaFileManager.Location location,
      String packageName,
      String relativeName,
      FileObject sibling) {
    URI uri = uriForFileObject(location, packageName, relativeName);
    var output = this.outputs.get(uri);
    if (output == null) {
      throw new IllegalStateException("Output file not found for URI: " + uri);
    }
    return output;
  }

  @Override
  public @Nullable JavaFileObject getJavaFileForInput(
      JavaFileManager.Location location, String className, JavaFileObject.Kind kind)
      throws IOException {
    if (kind == JavaFileObject.Kind.SOURCE && !location.isOutputLocation()) {
      var source = this.binaryNameIndex.get(className);
      if (source != null) {
        return source;
      }
    }

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
  public JavaFileObject getJavaFileForOutput(
      JavaFileManager.Location location,
      String className,
      JavaFileObject.Kind kind,
      FileObject sibling) {
    URI uri = uriForJavaFileObject(location, className, kind);
    var output = this.outputs.get(uri);
    if (output == null) {
      throw new IllegalStateException("Output file not found for URI: " + uri);
    }
    return output;
  }

  @Override
  public Iterable<JavaFileObject> list(
      JavaFileManager.Location location,
      String packageName,
      Set<JavaFileObject.Kind> kinds,
      boolean recurse)
      throws IOException {
    var result = new ArrayList<JavaFileObject>();

    // Add in-memory sources matching the package
    if (kinds.contains(JavaFileObject.Kind.SOURCE)
        && (location.equals(StandardLocation.SOURCE_PATH)
            || location.getName().equals(StandardLocation.SOURCE_PATH.getName()))) {
      if (recurse) {
        for (var entry : packageIndex.entries()) {
          var pkg = entry.getKey();
          if (pkg.equals(packageName)
              || pkg.startsWith(packageName + ".")
              || packageName.isEmpty()) {
            result.add(entry.getValue());
          }
        }
      } else {
        result.addAll(packageIndex.get(packageName));
      }
    }

    // Delegate to standard file manager for platform classes (java.*, javax.*, etc.)
    for (var obj : super.list(location, packageName, kinds, recurse)) {
      result.add(obj);
    }

    return result;
  }

  @Override
  public String inferBinaryName(JavaFileManager.Location location, JavaFileObject file) {
    var binaryName = reverseBinaryNameIndex.get(file);
    if (binaryName != null) {
      return binaryName;
    }
    return super.inferBinaryName(location, file);
  }

  @Override
  public boolean hasLocation(JavaFileManager.Location location) {
    if (location.equals(StandardLocation.SOURCE_PATH)) {
      return !inputs.isEmpty();
    }
    return super.hasLocation(location);
  }

  // Implementation this is based off uses a less strict check, which we not need to worry about
  // @Override
  // public boolean isSameFile(FileObject a, FileObject b) {
  //     throw new UnsupportedOperationException();
  // }
}
