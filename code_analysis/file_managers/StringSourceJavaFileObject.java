package me.djmm.java.code_analysis.file_managers;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.Charset;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import me.djmm.java.code_analysis.SourceText;

// Based off (mostly copied)
// https://github.com/google/compile-testing/blob/8259709a0f5995c7cc89f28425ca1a055bc5b24d/src/main/java/com/google/testing/compile/JavaFileObjects.java#L96
public class StringSourceJavaFileObject extends SimpleJavaFileObject {
  final String source;
  final long lastModified;
  private final String packageName;
  private final String binaryName;

  public StringSourceJavaFileObject(URI apparentUri, String source, long lastModified) {
    super(apparentUri, JavaFileObject.Kind.SOURCE);
    this.source = source;
    this.lastModified = lastModified;
    this.packageName = SourceText.extractPackageName(source);
    var path = apparentUri.getPath();
    var simpleName = "";
    if (path != null) {
      var lastSlash = path.lastIndexOf('/');
      var fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
      var dot = fileName.lastIndexOf('.');
      simpleName = dot >= 0 ? fileName.substring(0, dot) : fileName;
    }
    this.binaryName = packageName.isEmpty() ? simpleName : packageName + "." + simpleName;
  }

  /**
   * The package name extracted from the source's {@code package} declaration. Returns the empty
   * string for the default (unnamed) package.
   */
  public String packageName() {
    return packageName;
  }

  /**
   * The fully-qualified binary name (e.g. {@code "com.example.Foo"}). Derived from the package
   * declaration and the URI file name.
   */
  public String binaryName() {
    return binaryName;
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) {
    return source;
  }

  @Override
  public OutputStream openOutputStream() {
    throw new IllegalStateException();
  }

  @Override
  public InputStream openInputStream() {
    return new ByteArrayInputStream(source.getBytes(Charset.defaultCharset()));
  }

  @Override
  public Writer openWriter() {
    throw new IllegalStateException();
  }

  @Override
  public Reader openReader(boolean ignoreEncodingErrors) {
    return new StringReader(source);
  }

  @Override
  public long getLastModified() {
    return lastModified;
  }
}
