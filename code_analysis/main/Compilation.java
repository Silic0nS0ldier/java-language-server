package me.djmm.java.code_analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

/**
 * Results of a compilation
 */
public class Compilation {
    /**
     * Paths of inputs and their AST (internal com.sun.source variant).
     * If empty, AST parsing failed or was not possible.
     */
    public final ImmutableMap<Path, CompilationUnitTree> sources;

    /**
     * Diagnostics emitted during compilation.
     */
    public final ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics;

    /**
     * Errors that may have occuring during compilation.
     */
    public final Errors errors;

    private Compilation(
        ImmutableMap<Path, CompilationUnitTree> sources,
        ImmutableList<Diagnostic<? extends JavaFileObject>> diagnostics,
        Errors errors
    ) {
        this.sources = sources;
        this.diagnostics = diagnostics;
        this.errors = errors;
    }

    private static final JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();

    /**
     * Perform a new compilation.
     */
    public static Compilation perform(Collection<Path> files) {
        var fileManager = javaCompiler.getStandardFileManager(null, null, null);
        var compilationUnits = fileManager.getJavaFileObjectsFromPaths(files);
        ImmutableList.Builder<Diagnostic<? extends JavaFileObject>> diagnostics = ImmutableList.builder();
        ImmutableMap.Builder<Path, CompilationUnitTree> sources = ImmutableMap.builderWithExpectedSize(files.size());
        // TODO Unclear if internal `com.sun.tools.javac.api.JavacTool.getTask()` which allows customisable context is needed.
        // https://docs.oracle.com/javase/8/docs/api/javax/tools/JavaCompiler.html#getTask-java.io.Writer-javax.tools.JavaFileManager-javax.tools.DiagnosticListener-java.lang.Iterable-java.lang.Iterable-java.lang.Iterable-
        var task = javaCompiler.getTask(
            // TODO Provide an implementation so output isn't dumped to `System.err`
            /*out=java.io.Writer*/ null,
            // TODO Customise this properly so task instances operate over a stable snapshot
            //      Also allows more fancy things like in-memory only files
            /*fileManager=javax.tools.JavaFileManager*/ fileManager,
            /*diagnosticListener=javax.tools.DiagnosticListener<? super javax.tools.JavaFileObject>*/ diagnostics::add,
            // TODO Set compiler options
            /*options=Iterable<String*/ List.of(),
            // Classes to be processed by annotation processing, nothing for now.
            /*classes=Iterable<String>*/ List.of(),
            // Source to compile.
            /*compilationUnits=Iterable<? extends javax.tools.JavaFileObject*/ compilationUnits
        );

        // TODO Can use task.addTaskListener to track progress, likely not all that useful here

        IOException parseError = null;
        Throwable analyzeError = null;
        RuntimeException fallbackError = null;

        // Downcast to JavacTask to expose AST
        if (task instanceof JavacTask javacTask) {
            // Parse sources into AST
            try {
                var astRoots = javacTask.parse();
                for (var astRoot : astRoots) {
                    var path = fileManager.asPath(astRoot.getSourceFile());
                    sources.put(path, astRoot);
                }
            } catch (IOException e) {
                parseError = e;
            }

            // Run analysis
            try {
                // The results of borrow.task.analyze() are unreliable when errors are present
                // You can get at `Element` values using `com.sun.source.util.Trees`
                // For now results are discarded.
                javacTask.analyze();
            } catch (Throwable e) {
                // All kinds exceptions may be raised by `borrow.task.analyze`, but failures are
                // (mostly) safe.
                analyzeError = e;
            }
        } else {
            // TODO Mark basic analysis
            // TODO Track success/falure

            // Perform full compilation to run data-collecting side effects
            try {
                // TODO Use result as indicator for completeness of compilation results
                task.call();
            } catch (RuntimeException e) {
                fallbackError = e;
            }
        }

        return new Compilation(
            sources.build(),
            diagnostics.build(),
            new Errors(parseError, analyzeError, fallbackError)
        );
    }

    public static record Errors(
        /**
         * Potential error raised during parsing.
         */
        @Nullable IOException parse,
        /**
         * Potential error raised during analysis.
         */
        @Nullable Throwable analyze,
        /**
         * Potential error raised during full compilation fallback.
         */
        @Nullable RuntimeException fallback
    ) {}
}
