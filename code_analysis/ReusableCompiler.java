// Forked from JavacTaskImpl via org.javacs.ReusableCompiler
/*
 * Copyright (c) 2015, 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package me.djmm.java.code_analysis;

import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.api.JavacTool;
import com.sun.tools.javac.api.JavacTrees;
import com.sun.tools.javac.api.MultiTaskListener;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.comp.Annotate;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.comp.CompileStates;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.main.Arguments;
import com.sun.tools.javac.main.JavaCompiler;
import com.sun.tools.javac.model.JavacElements;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.DefinedBy;
import com.sun.tools.javac.util.DefinedBy.Api;
import com.sun.tools.javac.util.Log;
import jakarta.annotation.Nullable;
import java.io.Writer;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;

/**
 * Manages a reusable javac {@link Context} to avoid cold-start overhead on repeated compilations.
 *
 * <p>The javac compiler caches substantial state in its {@code Context} — JDK symbol tables,
 * resolved types, module system state, etc. By reusing the context between compilations, subsequent
 * compilations skip this initialization and run significantly faster.
 *
 * <p>The reuse is achieved by replacing the standard {@code JavaCompiler} and {@code Log} with
 * reusable variants that can be reset between compilations, and by clearing compilation-specific
 * state from the context after each use.
 *
 * <h2>Ownership model</h2>
 *
 * <p>Only one compilation task may be active at a time. Calling {@link #getTask} while a prior
 * {@link Borrow} is still outstanding automatically releases the prior borrow. After such an
 * automatic release, the prior borrow's {@link Borrow#task task} must not be used — doing so yields
 * undefined behaviour from javac (typically {@code NullPointerException} deep inside the compiler).
 *
 * <p>This class is not thread-safe. All method calls must be serialised by the caller.
 *
 * <p><b>This is an internal implementation detail. It relies on non-public javac APIs that may
 * change between JDK versions.</b>
 */
final class ReusableCompiler {
  private static final JavacTool JAVAC_TOOL = JavacTool.create();

  private List<String> currentOptions;
  private ReusableContext currentContext;
  private @Nullable Borrow activeBorrow;

  ReusableCompiler(List<String> initialOptions) {
    this.currentOptions = List.copyOf(initialOptions);
    this.currentContext = new ReusableContext(this.currentOptions);
  }

  /**
   * Create a javac task that reuses the internal context.
   *
   * <p>Any prior outstanding {@link Borrow} is automatically released. Its {@code task} must not be
   * used after this call — hold onto only the most recent Borrow.
   *
   * @return a fresh Borrow encapsulating the task. Close it when finished (or discard — the next
   *     call to {@code getTask} will release it).
   */
  Borrow getTask(
      Writer out,
      JavaFileManager fileManager,
      DiagnosticListener<? super JavaFileObject> diagnosticListener,
      List<String> options,
      Iterable<String> classes,
      Iterable<? extends JavaFileObject> compilationUnits) {

    // Auto-release any prior borrow so its task tear-down happens before we
    // build a new one on the same context.
    if (activeBorrow != null) {
      activeBorrow.release();
    }

    if (!options.equals(currentOptions)) {
      // Options changed — must create a fresh context since option values are
      // cached inside many components.
      currentOptions = List.copyOf(options);
      currentContext = new ReusableContext(currentOptions);
    }

    JavacTask task =
        JAVAC_TOOL.getTask(
            out,
            fileManager,
            diagnosticListener,
            options,
            classes,
            compilationUnits,
            currentContext);
    task.addTaskListener(currentContext);

    var borrow = new Borrow(task);
    activeBorrow = borrow;
    return borrow;
  }

  final class Borrow implements AutoCloseable {
    /**
     * The javac task for this borrow. Valid only while this Borrow is the compiler's active borrow
     * — i.e. until either {@link #close} is called on it, or a subsequent {@link
     * ReusableCompiler#getTask} call supersedes it.
     */
    public final JavacTask task;

    private boolean released;

    Borrow(JavacTask task) {
      this.task = task;
    }

    /**
     * @return {@code true} if this borrow is still the active one and its task can be used.
     */
    public boolean isActive() {
      return !released;
    }

    /**
     * Release resources for this compilation and make the context available for reuse. Idempotent —
     * safe to call multiple times.
     *
     * <p>Note: this is also invoked implicitly when a subsequent {@link ReusableCompiler#getTask}
     * call is made, so explicit closing is optional unless deterministic cleanup is required.
     */
    @Override
    public void close() {
      release();
    }

    private void release() {
      if (released) return;
      released = true;

      if (activeBorrow == this) {
        activeBorrow = null;
      }

      // Reset context state for reuse. If this fails, discard the context to
      // avoid leaving the compiler in a broken state.
      try {
        currentContext.clear();
        var method = JavacTaskImpl.class.getDeclaredMethod("cleanup");
        method.setAccessible(true);
        method.invoke(task);
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        currentContext = new ReusableContext(currentOptions);
      }
    }
  }

  /**
   * A javac {@link Context} that installs reusable variants of {@code JavaCompiler} and {@code
   * Log}, and supports clearing compilation-specific state between uses.
   */
  static final class ReusableContext extends Context implements TaskListener {
    final List<String> arguments;

    ReusableContext(List<String> arguments) {
      super();
      this.arguments = arguments;
      put(Log.logKey, ReusableLog.factory);
      put(JavaCompiler.compilerKey, ReusableJavaCompiler.factory);
    }

    void clear() {
      // Drop per-compilation entries that must be re-created each time
      drop(Arguments.argsKey);
      drop(DiagnosticListener.class);
      drop(Log.outKey);
      drop(Log.errKey);
      drop(JavaFileManager.class);
      drop(JavacTask.class);
      drop(JavacTrees.class);
      drop(JavacElements.class);

      if (ht.get(Log.logKey) instanceof ReusableLog) {
        // Not the first round — reset stateful components
        ((ReusableLog) Log.instance(this)).clear();
        Enter.instance(this).newRound();
        ((ReusableJavaCompiler) ReusableJavaCompiler.instance(this)).clear();
        Types.instance(this).newRound();
        Check.instance(this).newRound();
        Modules.instance(this).newRound();
        Annotate.instance(this).newRound();
        CompileStates.instance(this).clear();
        MultiTaskListener.instance(this).clear();
      }
    }

    @Override
    @DefinedBy(Api.COMPILER_TREE)
    public void finished(TaskEvent e) {
      // no-op
    }

    @Override
    @DefinedBy(Api.COMPILER_TREE)
    public void started(TaskEvent e) {
      // no-op
    }

    <T> void drop(Key<T> k) {
      ht.remove(k);
    }

    <T> void drop(Class<T> c) {
      ht.remove(key(c));
    }

    /** A JavaCompiler that suppresses close and reusability checks to allow context reuse. */
    static final class ReusableJavaCompiler extends JavaCompiler {
      static final Factory<JavaCompiler> factory = ReusableJavaCompiler::new;

      ReusableJavaCompiler(Context context) {
        super(context);
      }

      @Override
      public void close() {
        // Suppress — the context manages the lifecycle
      }

      void clear() {
        newRound();
      }

      @Override
      protected void checkReusable() {
        // Suppress — reuse is managed by ReusableContext
      }
    }

    /**
     * A Log that supports clearing state between compilations and lazily re-acquires the diagnostic
     * listener from the context.
     */
    static final class ReusableLog extends Log {
      static final Factory<Log> factory = ReusableLog::new;

      private final Context context;

      ReusableLog(Context context) {
        super(context);
        this.context = context;
      }

      // NOTE: `Log.clear()` is package-private on JDK 21 and public on JDK 25+.
      // Declaring the override as `public` compiles against both — narrowing
      // to package-private would fail on JDK 25 with "attempting to assign
      // weaker access privileges". This is the correct widening choice.
      public void clear() {
        recorded.clear();
        sourceMap.clear();
        nerrors = 0;
        nwarnings = 0;
        // Install a lazy listener that re-acquires from context on first use.
        // This is necessary because the Log is reused but the DiagnosticListener
        // changes per compilation.
        diagListener =
            new DiagnosticListener<>() {
              @Nullable DiagnosticListener<JavaFileObject> cached;

              @Override
              @DefinedBy(Api.COMPILER)
              @SuppressWarnings("unchecked")
              public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                if (cached == null) {
                  cached = context.get(DiagnosticListener.class);
                }
                cached.report(diagnostic);
              }
            };
      }
    }
  }
}
