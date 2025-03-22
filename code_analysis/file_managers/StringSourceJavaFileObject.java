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

// Based off (mostly copied) https://github.com/google/compile-testing/blob/8259709a0f5995c7cc89f28425ca1a055bc5b24d/src/main/java/com/google/testing/compile/JavaFileObjects.java#L96
public class StringSourceJavaFileObject extends SimpleJavaFileObject {
    final String source;
    final long lastModified;

    public StringSourceJavaFileObject(URI apparentUri, String source, long lastModified) {
      super(apparentUri, JavaFileObject.Kind.SOURCE);
      this.source = source;
      this.lastModified = lastModified;
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
