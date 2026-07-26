package me.djmm.java.code_analysis.file_managers;

import java.util.List;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Public factory for constructing a purely in-memory {@link JavaFileManager}.
 *
 * <p>Callers see only the {@link JavaFileManager} interface; the concrete implementation is
 * package-private so that behaviour can evolve without affecting the public surface.
 */
public final class InMemoryJavaFileManager {
  private InMemoryJavaFileManager() {}

  /**
   * Create a {@link JavaFileManager} that serves the given in-memory sources and delegates to the
   * platform's standard file manager for everything else (e.g. JDK modules).
   *
   * @param inputs source files whose URIs must use the {@code mem} scheme
   * @return a new in-memory file manager
   * @throws IllegalArgumentException if any input's URI scheme is not {@code mem}
   */
  public static JavaFileManager create(List<? extends JavaFileObject> inputs) {
    return new InMemoryJavaFileManagerImpl(inputs);
  }
}
