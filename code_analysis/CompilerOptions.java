package me.djmm.java.code_analysis;

import com.google.common.collect.ImmutableList;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Configuration for a compilation.
 *
 * <p>Instances are immutable and created via the {@link #builder()} or {@link #defaults()} factory.
 * The {@code with*} methods return new instances, leaving the original unchanged.
 */
public final class CompilerOptions {
  private final ImmutableList<Path> classPath;
  private final ImmutableList<String> additionalFlags;

  private CompilerOptions(ImmutableList<Path> classPath, ImmutableList<String> additionalFlags) {
    this.classPath = classPath;
    this.additionalFlags = additionalFlags;
  }

  /** Default options suitable for analysis: annotation processing disabled, debug info enabled. */
  public static CompilerOptions defaults() {
    return new CompilerOptions(ImmutableList.of(), ImmutableList.of());
  }

  public static Builder builder() {
    return new Builder();
  }

  public ImmutableList<Path> classPath() {
    return classPath;
  }

  public ImmutableList<String> additionalFlags() {
    return additionalFlags;
  }

  public CompilerOptions withClassPath(Collection<Path> classPath) {
    return new CompilerOptions(ImmutableList.copyOf(classPath), this.additionalFlags);
  }

  public CompilerOptions withAdditionalFlags(Collection<String> flags) {
    return new CompilerOptions(this.classPath, ImmutableList.copyOf(flags));
  }

  /** Compute the full list of javac flags from this configuration. */
  List<String> toFlags() {
    var flags = new ArrayList<String>();

    if (!classPath.isEmpty()) {
      flags.add("-classpath");
      flags.add(
          classPath.stream()
              .map(Path::toString)
              .reduce((a, b) -> a + File.pathSeparator + b)
              .orElse(""));
    }

    // Analysis-oriented defaults
    flags.add("-proc:none");
    flags.add("-g");

    flags.addAll(additionalFlags);
    return List.copyOf(flags);
  }

  public static final class Builder {
    private final ArrayList<Path> classPath = new ArrayList<>();
    private final ArrayList<String> additionalFlags = new ArrayList<>();

    private Builder() {}

    public Builder classPath(Collection<Path> entries) {
      this.classPath.addAll(entries);
      return this;
    }

    public Builder classPathEntry(Path entry) {
      Objects.requireNonNull(entry, "entry");
      this.classPath.add(entry);
      return this;
    }

    public Builder flag(String flag) {
      Objects.requireNonNull(flag, "flag");
      this.additionalFlags.add(flag);
      return this;
    }

    public Builder flags(Collection<String> flags) {
      this.additionalFlags.addAll(flags);
      return this;
    }

    public CompilerOptions build() {
      return new CompilerOptions(
          ImmutableList.copyOf(classPath), ImmutableList.copyOf(additionalFlags));
    }
  }
}
