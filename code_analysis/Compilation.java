package me.djmm.java.code_analysis;

import static javax.tools.ToolProvider.getSystemJavaCompiler;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collection;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import me.djmm.java.code_analysis.file_managers.InMemoryJavaFileManager;
import me.djmm.java.code_analysis.file_managers.StringSourceJavaFileObject;

/**
 * An immutable snapshot of compilation results.
 *
 * <p>Create instances via the static factory methods {@link #create(Collection)} or {@link
 * #create(Collection, CompilerOptions)}.
 *
 * <p>Provides access to parsed syntax trees, diagnostics, and (when analysis succeeds) a {@link
 * SemanticModel} for type-level queries.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * var result = Compilation.create(List.of(
 *     SourceText.of("com/example/Foo.java", "package com.example; public class Foo {}")
 * ));
 *
 * for (var diag : result.diagnostics()) {
 *     System.out.println(diag.getMessage(null));
 * }
 *
 * result.semanticModel().ifPresent(model -> {
 *     // Use model.trees(), model.elements(), model.types()
 * });
 * }</pre>
 */
public final class Compilation {
  private final ImmutableMap<URI, CompilationUnitTree> syntaxTrees;
  private final ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics;
  private final CompilationPhase phase;
  private final @Nullable SemanticModel semanticModel;
  private final ImmutableList<Throwable> internalErrors;
  private final String compilerOutput;

  private Compilation(
      ImmutableMap<URI, CompilationUnitTree> syntaxTrees,
      ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics,
      CompilationPhase phase,
      @Nullable SemanticModel semanticModel,
      ImmutableList<Throwable> internalErrors,
      String compilerOutput) {
    this.syntaxTrees = syntaxTrees;
    this.diagnostics = diagnostics;
    this.phase = phase;
    this.semanticModel = semanticModel;
    this.internalErrors = internalErrors;
    this.compilerOutput = compilerOutput;
  }

  /**
   * Package-private factory for constructing a Compilation from pre-assembled parts. Used by {@link
   * IncrementalCompiler} which manages the javac lifecycle itself.
   */
  static Compilation fromParts(
      ImmutableMap<URI, CompilationUnitTree> syntaxTrees,
      ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics,
      CompilationPhase phase,
      @Nullable SemanticModel semanticModel,
      ImmutableList<Throwable> internalErrors,
      String compilerOutput) {
    return new Compilation(
        syntaxTrees, diagnostics, phase, semanticModel, internalErrors, compilerOutput);
  }

  private static final JavaCompiler JAVAC = getSystemJavaCompiler();

  /**
   * Compile the given sources with default options.
   *
   * @param sources the source files to compile; must not be empty
   * @return an immutable compilation result
   * @throws IllegalArgumentException if {@code sources} is empty
   */
  public static Compilation create(Collection<SourceText> sources) {
    return create(sources, CompilerOptions.defaults());
  }

  /**
   * Compile the given sources with the specified options.
   *
   * @param sources the source files to compile; must not be empty
   * @param options compiler configuration
   * @return an immutable compilation result
   * @throws IllegalArgumentException if {@code sources} is empty
   */
  public static Compilation create(Collection<SourceText> sources, CompilerOptions options) {
    if (sources.isEmpty()) {
      throw new IllegalArgumentException("No sources to compile");
    }

    // Convert SourceText → JavaFileObject
    var fileObjects =
        sources.stream()
            .map(s -> new StringSourceJavaFileObject(s.uri(), s.content(), s.version()))
            .toList();

    var fileManager = InMemoryJavaFileManager.create(fileObjects);
    var diagnostics = ImmutableList.<Diagnostic<? extends JavaFileObject>>builder();
    var compilerOutput = new StringWriter();

    var task =
        JAVAC.getTask(
            compilerOutput,
            fileManager,
            diagnostics::add,
            options.toFlags(),
            List.of(),
            fileObjects);

    var trees = ImmutableMap.<URI, CompilationUnitTree>builderWithExpectedSize(sources.size());
    var internalErrors = ImmutableList.<Throwable>builder();
    var phase = CompilationPhase.PARSED;
    SemanticModel model = null;

    if (task instanceof JavacTask javacTask) {
      // Phase 1: Parse
      try {
        for (var unit : javacTask.parse()) {
          trees.put(unit.getSourceFile().toUri(), unit);
        }
      } catch (IOException e) {
        internalErrors.add(e);
        return new Compilation(
            trees.build(),
            diagnostics.build(),
            phase,
            null,
            internalErrors.build(),
            compilerOutput.toString());
      }

      // Phase 2: Semantic analysis
      try {
        javacTask.analyze();
        phase = CompilationPhase.ANALYZED;
      } catch (Throwable e) {
        // Analysis failures are common when sources have errors.
        // The partial results are still useful.
        internalErrors.add(e);
      }
      model = new SemanticModel(javacTask);
    } else {
      // Non-javac compiler fallback (extremely rare)
      try {
        task.call();
        phase = CompilationPhase.ANALYZED;
      } catch (RuntimeException e) {
        internalErrors.add(e);
      }
    }

    return new Compilation(
        trees.build(),
        diagnostics.build(),
        phase,
        model,
        internalErrors.build(),
        compilerOutput.toString());
  }

  /**
   * Parsed syntax trees keyed by source URI. Empty if parsing failed entirely (check {@link
   * #internalErrors()}).
   */
  public ImmutableMap<URI, CompilationUnitTree> syntaxTrees() {
    return syntaxTrees;
  }

  /** Diagnostics emitted during compilation (errors, warnings, notes). */
  public ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics() {
    return diagnostics;
  }

  /** How far the compilation progressed. */
  public CompilationPhase phase() {
    return phase;
  }

  /**
   * Semantic model providing access to type information, symbol resolution, and tree-to-element
   * mappings. Available when compilation at least attempted analysis (even if analysis produced
   * errors).
   *
   * <p>Returns {@code null} only if the underlying compiler is not javac (extremely rare).
   */
  public @Nullable SemanticModel semanticModel() {
    return semanticModel;
  }

  /**
   * Internal errors (exceptions) that occurred during compilation. These are distinct from {@link
   * #diagnostics()} — they represent failures in the compilation process itself, not problems in
   * the source code.
   */
  public ImmutableList<Throwable> internalErrors() {
    return internalErrors;
  }

  /** Raw text output from the compiler (typically written to stderr). */
  public String compilerOutput() {
    return compilerOutput;
  }
}
