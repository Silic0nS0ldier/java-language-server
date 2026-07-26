package me.djmm.java.code_analysis;

import java.net.URI;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable representation of a source file's content, identified by URI.
 *
 * <p>URIs should follow a path convention matching the Java package structure. For in-memory
 * sources, use the {@code mem} scheme (e.g., {@code mem:///com/example/Foo.java}).
 *
 * @param uri Unique identifier for this source file.
 * @param content The full source text.
 * @param version Monotonically increasing version number for change tracking. Use 0 for unversioned
 *     sources.
 */
public record SourceText(URI uri, String content, long version) {
  private static final Pattern PACKAGE_PATTERN =
      Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

  public SourceText {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(content, "content");
    if (version < 0) {
      throw new IllegalArgumentException("version must be non-negative");
    }
  }

  /**
   * Create a source text with a {@code mem:///} URI and version 0.
   *
   * @param path Path portion of the URI, e.g. {@code "com/example/Foo.java"}.
   * @param content The Java source code.
   */
  public static SourceText of(String path, String content) {
    Objects.requireNonNull(path, "path");
    // Strip leading slash if present to avoid double slashes
    var normalised = path.startsWith("/") ? path.substring(1) : path;
    return new SourceText(URI.create("mem:///" + normalised), content, 0);
  }

  /**
   * Extracts the package name declared in this source. Returns the empty string for the default
   * (unnamed) package.
   */
  public String packageName() {
    return extractPackageName(content);
  }

  /**
   * Derives the simple class name from the URI's file name. For example, {@code
   * mem:///com/example/Foo.java} yields {@code "Foo"}.
   */
  public String simpleClassName() {
    var path = uri.getPath();
    if (path == null) {
      return "";
    }
    var lastSlash = path.lastIndexOf('/');
    var fileName = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    var dot = fileName.lastIndexOf('.');
    return dot >= 0 ? fileName.substring(0, dot) : fileName;
  }

  /**
   * Derives the binary name (fully-qualified class name) from the package declaration and URI file
   * name. For example, a source with {@code package com.example;} at URI {@code
   * mem:///com/example/Foo.java} yields {@code "com.example.Foo"}.
   */
  public String binaryName() {
    var pkg = packageName();
    var simpleName = simpleClassName();
    return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
  }

  /**
   * Extract the package name from Java source content. Returns the empty string for the default
   * (unnamed) package.
   */
  public static String extractPackageName(String source) {
    var matcher = PACKAGE_PATTERN.matcher(source);
    return matcher.find() ? matcher.group(1) : "";
  }
}
