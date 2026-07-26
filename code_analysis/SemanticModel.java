package me.djmm.java.code_analysis;

import com.sun.source.util.JavacTask;
import com.sun.source.util.Trees;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Provides access to semantic analysis results of a compilation.
 *
 * <p>Wraps the javac APIs for querying type information, symbol resolution,
 * and mappings between syntax trees and semantic elements.
 *
 * <p>The semantic model is valid for the lifetime of the {@link Compilation} that
 * produced it. Do not retain references across compilations.
 */
public final class SemanticModel {
    private final JavacTask task;

    SemanticModel(JavacTask task) {
        this.task = task;
    }

    /**
     * Tree utilities for mapping between {@code CompilationUnitTree} nodes and
     * {@code Element}/{@code TypeMirror} representations.
     */
    public Trees trees() {
        return Trees.instance(task);
    }

    /**
     * Utilities for operating on program elements (packages, classes, methods, fields).
     */
    public Elements elements() {
        return task.getElements();
    }

    /**
     * Utilities for operating on types (subtyping, erasure, etc.).
     */
    public Types types() {
        return task.getTypes();
    }

    /**
     * Direct access to the underlying {@code JavacTask}. Escape hatch for operations
     * not covered by the higher-level API. Use with care — the task's internal state
     * is tied to the compilation lifecycle.
     */
    public JavacTask task() {
        return task;
    }
}
