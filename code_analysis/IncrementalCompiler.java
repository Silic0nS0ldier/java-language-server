package me.djmm.java.code_analysis;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.JavacTask;
import jakarta.annotation.Nullable;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import me.djmm.java.code_analysis.file_managers.InMemoryJavaFileManager;
import me.djmm.java.code_analysis.file_managers.StringSourceJavaFileObject;

/**
 * A stateful compiler that supports efficient incremental re-compilation.
 *
 * <p>Maintains a set of source files and reuses internal javac compiler state
 * across compilations. When sources change and {@link #compile()} is called again,
 * the JDK symbol tables, module system state, and other cached compiler data are
 * reused — avoiding the cold-start overhead that dominates fresh compilations.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (var compiler = IncrementalCompiler.create()) {
 *     // Initial compilation
 *     compiler.addOrUpdateSource(SourceText.of("Foo.java", "class Foo {}"));
 *     compiler.addOrUpdateSource(SourceText.of("Bar.java", "class Bar extends Foo {}"));
 *     var result1 = compiler.compile();
 *
 *     // Fast incremental re-compilation after editing Foo.java
 *     compiler.addOrUpdateSource(new SourceText(
 *         URI.create("mem:///Foo.java"), "class Foo { int x; }", 1));
 *     var result2 = compiler.compile();  // reuses compiler context
 *
 *     // No changes — returns cached result
 *     var result3 = compiler.compile();  // same object as result2
 * }
 * }</pre>
 *
 * <h2>Thread safety</h2>
 * <p>This class is <b>not</b> thread-safe. All method calls must be serialised by the caller.
 * Concurrent calls to {@link #compile()} will throw {@link IllegalStateException}.
 */
public final class IncrementalCompiler implements AutoCloseable {
    private final CompilerOptions options;
    private final ReusableCompiler reusableCompiler;

    /** Current sources, keyed by URI. Mutable; order preserved for deterministic compilation. */
    private final LinkedHashMap<URI, SourceText> sources = new LinkedHashMap<>();

    /**
     * Monotonically increasing counter bumped whenever sources are added, updated, or removed.
     * Compared against {@code lastCompilationGeneration} to determine if re-compilation is needed.
     */
    private long generation;

    /** Generation at which {@code cachedCompilation} was produced. */
    private long lastCompilationGeneration = -1;

    /** Cached result from the most recent {@link #compile()} call. */
    private @Nullable Compilation cachedCompilation;

    /**
     * The borrow from the most recent compilation. Kept alive so that the {@link SemanticModel}
     * in {@code cachedCompilation} remains valid. Closed when a new compilation starts or when
     * this compiler is closed.
     */
    private @Nullable ReusableCompiler.Borrow activeBorrow;

    private boolean closed;

    private IncrementalCompiler(CompilerOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        this.reusableCompiler = new ReusableCompiler(options.toFlags());
    }

    /**
     * Create an incremental compiler with default options.
     */
    public static IncrementalCompiler create() {
        return new IncrementalCompiler(CompilerOptions.defaults());
    }

    /**
     * Create an incremental compiler with the given options.
     */
    public static IncrementalCompiler create(CompilerOptions options) {
        return new IncrementalCompiler(options);
    }

    /**
     * Add a new source or replace an existing one (matched by URI).
     *
     * <p>If the source at this URI has not changed (same content and version),
     * this is a no-op and does not trigger re-compilation.
     */
    public void addOrUpdateSource(SourceText source) {
        Objects.requireNonNull(source, "source");
        checkNotClosed();
        var existing = sources.get(source.uri());
        if (existing != null && existing.equals(source)) {
            return; // No change
        }
        sources.put(source.uri(), source);
        generation++;
    }

    /**
     * Remove a source file. No-op if the URI is not tracked.
     */
    public void removeSource(URI uri) {
        Objects.requireNonNull(uri, "uri");
        checkNotClosed();
        if (sources.remove(uri) != null) {
            generation++;
        }
    }

    /**
     * Replace all sources at once. More efficient than individual add/remove calls
     * when the full set of sources is known.
     *
     * <p>If the new set is identical to the current set, this is a no-op.
     */
    public void setSources(Collection<SourceText> newSources) {
        Objects.requireNonNull(newSources, "newSources");
        checkNotClosed();
        var newMap = new LinkedHashMap<URI, SourceText>(newSources.size());
        for (var s : newSources) {
            newMap.put(s.uri(), s);
        }
        if (newMap.equals(sources)) {
            return; // No change
        }
        sources.clear();
        sources.putAll(newMap);
        generation++;
    }

    /**
     * An unmodifiable view of the current sources.
     */
    public Map<URI, SourceText> sources() {
        return java.util.Collections.unmodifiableMap(sources);
    }

    /**
     * Compile all current sources and return an immutable result.
     *
     * <p>If no sources have changed since the last call, returns the cached result
     * (same object identity). Otherwise, performs a new compilation reusing the internal
     * compiler context for performance.
     *
     * <p><b>Semantic model lifecycle:</b> The {@link SemanticModel} from a previous compilation
     * becomes invalid when a new compilation starts. Retain the {@link Compilation} reference
     * but do not use its semantic model after calling {@code compile()} again.
     *
     * @return the compilation result
     * @throws IllegalStateException if no sources have been added, or if the compiler is closed
     */
    public Compilation compile() {
        checkNotClosed();
        if (sources.isEmpty()) {
            throw new IllegalStateException("No sources to compile");
        }

        // Fast path: nothing changed since last compilation
        if (cachedCompilation != null && generation == lastCompilationGeneration) {
            return cachedCompilation;
        }

        // Note: reusableCompiler.getTask() implicitly releases activeBorrow.
        // We just need to invalidate our cached reference and start a new compilation.
        activeBorrow = null;
        cachedCompilation = compileWithReusableContext();
        lastCompilationGeneration = generation;
        return cachedCompilation;
    }

    private Compilation compileWithReusableContext() {
        // Convert SourceText → JavaFileObject
        var fileObjects = sources.values().stream()
            .map(s -> new StringSourceJavaFileObject(s.uri(), s.content(), s.version()))
            .toList();

        var fileManager = InMemoryJavaFileManager.create(fileObjects);
        var diagnostics = ImmutableList.<Diagnostic<? extends JavaFileObject>>builder();
        var compilerOutput = new StringWriter();

        var borrow = reusableCompiler.getTask(
                compilerOutput, fileManager, diagnostics::add,
                options.toFlags(), List.of(), fileObjects);

        var trees = ImmutableMap.<URI, CompilationUnitTree>builderWithExpectedSize(sources.size());
        var internalErrors = ImmutableList.<Throwable>builder();
        var phase = CompilationPhase.PARSED;
        SemanticModel model = null;

        if (borrow.task instanceof JavacTask javacTask) {
            // Phase 1: Parse
            try {
                for (var unit : javacTask.parse()) {
                    trees.put(unit.getSourceFile().toUri(), unit);
                }
            } catch (IOException e) {
                internalErrors.add(e);
                borrow.close();
                return Compilation.fromParts(
                    trees.build(), diagnostics.build(), phase,
                    null, internalErrors.build(), compilerOutput.toString()
                );
            }

            // Phase 2: Semantic analysis
            try {
                javacTask.analyze();
                phase = CompilationPhase.ANALYZED;
            } catch (Throwable e) {
                internalErrors.add(e);
            }
            model = new SemanticModel(javacTask);
        } else {
            borrow.close();
        }

        // Keep the borrow alive so SemanticModel remains valid
        activeBorrow = borrow;

        return Compilation.fromParts(
            trees.build(), diagnostics.build(), phase,
            model, internalErrors.build(), compilerOutput.toString()
        );
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            cachedCompilation = null;
            if (activeBorrow != null) {
                activeBorrow.close();
                activeBorrow = null;
            }
        }
    }

    private void checkNotClosed() {
        if (closed) {
            throw new IllegalStateException("IncrementalCompiler has been closed");
        }
    }
}
