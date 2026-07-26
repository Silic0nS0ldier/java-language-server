package me.djmm.java.code_analysis.actions;

import com.google.common.collect.ImmutableList;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LineMap;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import jakarta.annotation.Nullable;
import java.net.URI;
import java.util.Objects;
import javax.lang.model.element.Element;
import me.djmm.java.code_analysis.Compilation;
import me.djmm.java.code_analysis.SemanticModel;
import me.djmm.java.code_analysis.symbols.Reference;
import me.djmm.java.code_analysis.text.SourceRange;

/**
 * Finds all references to a symbol within a {@link Compilation}.
 *
 * <p>The result includes both the declaration (when present in the compiled sources)
 * and all use sites. References are located exactly on the identifier — not on the
 * enclosing expression — matching what an editor would highlight.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * var compilation = Compilation.create(sources);
 *
 * // Locate the symbol at a position, then find all its references
 * var references = FindReferences.atPosition(
 *     compilation,
 *     URI.create("mem:///com/example/Foo.java"),
 *     offsetOfSymbol
 * );
 *
 * for (var ref : references) {
 *     System.out.printf("%s:%d:%d %s%n",
 *         ref.uri(), ref.range().startLine(), ref.range().startColumn(), ref.kind());
 * }
 * }</pre>
 */
public final class FindReferences {
    private FindReferences() {}

    /**
     * Locate the symbol at the given character offset in the specified source and
     * find all references to it in the compilation.
     *
     * @param compilation the compilation to search
     * @param uri         URI of the source containing the target symbol
     * @param offset      zero-based character offset of the target symbol
     * @return an immutable list of references, or an empty list if no symbol was
     *         found at that offset
     * @throws NoSuchSourceException if {@code uri} is not present in the compilation
     * @throws IllegalStateException if the compilation has no {@link SemanticModel}
     */
    public static ImmutableList<Reference> atPosition(
            Compilation compilation, URI uri, int offset) {
        var target = resolveElement(compilation, uri, offset);
        if (target == null) {
            return ImmutableList.of();
        }
        return forElement(compilation, target);
    }

    /**
     * Resolve the semantic element at the given character offset in the specified source.
     *
     * <p>This is useful for actions that need the element itself (e.g. to expand a rename
     * to related declarations) rather than only its references.
     *
     * @param compilation the compilation to search
     * @param uri         URI of the source containing the target position
     * @param offset      zero-based character offset
     * @return the element at that position, or {@code null} if no symbol is present
     * @throws NoSuchSourceException if {@code uri} is not present in the compilation
     * @throws IllegalStateException if the compilation has no {@link SemanticModel}
     */
    public static @Nullable Element resolveElement(
            Compilation compilation, URI uri, int offset) {
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(uri, "uri");
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be non-negative");
        }

        var model = compilation.semanticModel();
        if (model == null) {
            throw new IllegalStateException(
                "Compilation has no semantic model — cannot resolve references");
        }

        var root = compilation.syntaxTrees().get(uri);
        if (root == null) {
            throw new NoSuchSourceException(uri);
        }

        var trees = model.trees();
        var pathAtCursor = new FindNodeAtPosition(trees, root).scan(root, (long) offset);
        if (pathAtCursor == null) {
            return null;
        }
        return trees.getElement(pathAtCursor);
    }

    /**
     * Find all references to the given element in the compilation.
     *
     * <p>References are matched by element equality — for javac elements within a single
     * compilation this is reference equality on the underlying symbol.
     *
     * @param compilation the compilation to search
     * @param target      the element whose references to find
     * @return an immutable list of references (may be empty)
     * @throws IllegalStateException if the compilation has no {@link SemanticModel}
     */
    public static ImmutableList<Reference> forElement(Compilation compilation, Element target) {
        Objects.requireNonNull(compilation, "compilation");
        Objects.requireNonNull(target, "target");

        var model = compilation.semanticModel();
        if (model == null) {
            throw new IllegalStateException(
                "Compilation has no semantic model — cannot resolve references");
        }

        var trees = model.trees();
        var positions = trees.getSourcePositions();
        var results = ImmutableList.<Reference>builder();

        for (var root : compilation.syntaxTrees().values()) {
            var scanner = new ReferenceScanner(trees, positions, root, target);
            scanner.scan(root, results);
        }

        return results.build();
    }

    /**
     * Thrown when a URI cannot be found in a compilation's syntax trees.
     */
    public static final class NoSuchSourceException extends RuntimeException {
        private final URI uri;

        NoSuchSourceException(URI uri) {
            super("Source not found in compilation: " + uri);
            this.uri = uri;
        }

        public URI uri() {
            return uri;
        }
    }

    /**
     * Visits every identifier-like node in a compilation unit, recording those whose
     * resolved element equals the target.
     */
    private static final class ReferenceScanner
            extends TreePathScanner<Void, ImmutableList.Builder<Reference>> {
        private final Trees trees;
        private final SourcePositions positions;
        private final CompilationUnitTree root;
        private final URI uri;
        private final Element target;
        private final LineMap lineMap;
        private final CharSequence sourceContent;

        private @Nullable ClassTree enclosingClass;

        ReferenceScanner(Trees trees, SourcePositions positions,
                         CompilationUnitTree root, Element target) {
            this.trees = trees;
            this.positions = positions;
            this.root = root;
            this.uri = root.getSourceFile().toUri();
            this.target = target;
            this.lineMap = root.getLineMap();
            this.sourceContent = readContent(root);
        }

        private static CharSequence readContent(CompilationUnitTree root) {
            try {
                return root.getSourceFile().getCharContent(true);
            } catch (Exception e) {
                return "";
            }
        }

        @Override
        public Void visitClass(ClassTree node, ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                addRange(node, node.getSimpleName(), Reference.Kind.DECLARATION, results);
            }
            var prev = enclosingClass;
            enclosingClass = node;
            try {
                return super.visitClass(node, results);
            } finally {
                enclosingClass = prev;
            }
        }

        @Override
        public Void visitMethod(MethodTree node, ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                var name = node.getName();
                // Constructors are named "<init>" in the tree but "ClassName" in source.
                if (name.contentEquals("<init>") && enclosingClass != null) {
                    name = enclosingClass.getSimpleName();
                }
                addRange(node, name, Reference.Kind.DECLARATION, results);
            }
            return super.visitMethod(node, results);
        }

        @Override
        public Void visitVariable(VariableTree node, ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                addRange(node, node.getName(), Reference.Kind.DECLARATION, results);
            }
            return super.visitVariable(node, results);
        }

        @Override
        public Void visitIdentifier(IdentifierTree node, ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                addRange(node, node.getName(), Reference.Kind.USE, results);
            }
            return super.visitIdentifier(node, results);
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree node, ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                addRange(node, node.getIdentifier(), Reference.Kind.USE, results);
            }
            return super.visitMemberSelect(node, results);
        }

        @Override
        public Void visitMemberReference(MemberReferenceTree node,
                                         ImmutableList.Builder<Reference> results) {
            if (isTarget(getCurrentPath())) {
                addRange(node, node.getName(), Reference.Kind.USE, results);
            }
            return super.visitMemberReference(node, results);
        }

        @Override
        public Void visitNewClass(NewClassTree node, ImmutableList.Builder<Reference> results) {
            // For `new Foo(...)`, the resolved element is the constructor.
            // Match on either the constructor or the enclosing type.
            if (isTarget(getCurrentPath())) {
                addNewClassRange(node, results);
            }
            return super.visitNewClass(node, results);
        }

        private boolean isTarget(TreePath path) {
            var element = trees.getElement(path);
            return target.equals(element);
        }

        private void addRange(Tree tree, CharSequence name, Reference.Kind kind,
                              ImmutableList.Builder<Reference> results) {
            var range = rangeOfName(tree, name);
            if (range != null) {
                results.add(new Reference(uri, range, kind));
            }
        }

        private void addNewClassRange(NewClassTree tree, ImmutableList.Builder<Reference> results) {
            // Range the class identifier portion of `new Foo(...)`.
            var identifier = tree.getIdentifier();
            var start = (int) positions.getStartPosition(root, identifier);
            var end = (int) positions.getEndPosition(root, identifier);
            if (start < 0 || end < 0) return;
            results.add(new Reference(uri, buildRange(start, end), Reference.Kind.USE));
        }

        private @Nullable SourceRange rangeOfName(Tree tree, CharSequence name) {
            var treeStart = (int) positions.getStartPosition(root, tree);
            var treeEnd = (int) positions.getEndPosition(root, tree);
            if (treeStart < 0 || treeEnd < 0) return null;

            // Scan within the tree span for the identifier text. This avoids highlighting
            // the entire expression when the caller wants just the name.
            var nameStart = indexOfName(sourceContent, name, treeStart, treeEnd);
            if (nameStart < 0) {
                // Fall back to the full tree range if we can't locate the name (rare —
                // usually happens with synthetic elements).
                return buildRange(treeStart, treeEnd);
            }
            return buildRange(nameStart, nameStart + name.length());
        }

        private SourceRange buildRange(int startOffset, int endOffset) {
            var startLine = (int) lineMap.getLineNumber(startOffset);
            var startColumn = (int) lineMap.getColumnNumber(startOffset);
            var endLine = (int) lineMap.getLineNumber(endOffset);
            var endColumn = (int) lineMap.getColumnNumber(endOffset);
            return new SourceRange(
                startOffset, endOffset,
                startLine, startColumn,
                endLine, endColumn
            );
        }

        /**
         * Find the offset of {@code name} within the source span {@code [start, end)}.
         * Simple substring scan — sufficient because the name is always somewhere in
         * the span of any tree that references it.
         */
        private static int indexOfName(CharSequence source, CharSequence name, int start, int end) {
            if (source.length() == 0) return -1;
            var nameLen = name.length();
            var scanEnd = Math.min(end, source.length()) - nameLen;
            outer:
            for (var i = start; i <= scanEnd; i++) {
                for (var j = 0; j < nameLen; j++) {
                    if (source.charAt(i + j) != name.charAt(j)) continue outer;
                }
                // Verify identifier boundaries — no adjacent identifier characters.
                if (i > 0 && Character.isJavaIdentifierPart(source.charAt(i - 1))) continue;
                var after = i + nameLen;
                if (after < source.length() && Character.isJavaIdentifierPart(source.charAt(after))) continue;
                return i;
            }
            return -1;
        }
    }

    /**
     * Locates the deepest tree node whose name-span contains a given offset.
     */
    private static final class FindNodeAtPosition extends TreePathScanner<TreePath, Long> {
        private final Trees trees;
        private final CompilationUnitTree root;
        private final SourcePositions positions;
        private final CharSequence sourceContent;

        FindNodeAtPosition(Trees trees, CompilationUnitTree root) {
            this.trees = trees;
            this.root = root;
            this.positions = trees.getSourcePositions();
            this.sourceContent = ReferenceScanner.readContent(root);
        }

        @Override
        public TreePath visitClass(ClassTree node, Long find) {
            if (matchesName(node, node.getSimpleName(), find)) {
                return getCurrentPath();
            }
            return super.visitClass(node, find);
        }

        @Override
        public TreePath visitMethod(MethodTree node, Long find) {
            var name = node.getName();
            if (name.contentEquals("<init>")) {
                // Use surrounding class name for constructors — we can find it by walking up.
                var parent = getCurrentPath().getParentPath();
                if (parent != null && parent.getLeaf() instanceof ClassTree cls) {
                    name = cls.getSimpleName();
                }
            }
            if (matchesName(node, name, find)) {
                return getCurrentPath();
            }
            return super.visitMethod(node, find);
        }

        @Override
        public TreePath visitVariable(VariableTree node, Long find) {
            if (matchesName(node, node.getName(), find)) {
                return getCurrentPath();
            }
            return super.visitVariable(node, find);
        }

        @Override
        public TreePath visitIdentifier(IdentifierTree node, Long find) {
            if (matchesName(node, node.getName(), find)) {
                return getCurrentPath();
            }
            return super.visitIdentifier(node, find);
        }

        @Override
        public TreePath visitMemberSelect(MemberSelectTree node, Long find) {
            if (matchesName(node, node.getIdentifier(), find)) {
                return getCurrentPath();
            }
            return super.visitMemberSelect(node, find);
        }

        @Override
        public TreePath visitMemberReference(MemberReferenceTree node, Long find) {
            if (matchesName(node, node.getName(), find)) {
                return getCurrentPath();
            }
            return super.visitMemberReference(node, find);
        }

        @Override
        public TreePath visitNewClass(NewClassTree node, Long find) {
            var identifier = node.getIdentifier();
            var start = positions.getStartPosition(root, identifier);
            var end = positions.getEndPosition(root, identifier);
            if (start >= 0 && start <= find && find < end) {
                return getCurrentPath();
            }
            return super.visitNewClass(node, find);
        }

        @Override
        public TreePath reduce(TreePath r1, TreePath r2) {
            return r1 != null ? r1 : r2;
        }

        private boolean matchesName(Tree tree, CharSequence name, long find) {
            var start = (int) positions.getStartPosition(root, tree);
            var end = (int) positions.getEndPosition(root, tree);
            if (start < 0 || end < 0) return false;
            var nameStart = ReferenceScanner.indexOfName(sourceContent, name, start, end);
            if (nameStart < 0) return false;
            var nameEnd = nameStart + name.length();
            return nameStart <= find && find < nameEnd;
        }
    }
}
