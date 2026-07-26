package me.djmm.java.code_analysis.actions;

import com.google.common.collect.ImmutableList;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.TypeElement;
import me.djmm.java.code_analysis.Compilation;
import me.djmm.java.code_analysis.symbols.Reference;
import me.djmm.java.code_analysis.text.TextEdit;

/**
 * Rename a symbol and all its references.
 *
 * <p>Builds on {@link FindReferences} — the same discovery logic locates every declaration and use,
 * and each reference becomes a {@link TextEdit} that replaces the identifier with the new name.
 *
 * <p>When renaming a class, constructor declarations sharing that name are renamed too.
 * Symmetrically, renaming a constructor also renames its enclosing class and any sibling
 * constructors, so the source remains valid Java.
 *
 * <h2>Example</h2>
 *
 * <pre>{@code
 * var edits = Rename.atPosition(
 *     compilation,
 *     URI.create("mem:///com/example/Foo.java"),
 *     offsetOfSymbol,
 *     "newName"
 * );
 *
 * // Apply edits — sort desc by startOffset within a file so earlier edits do not
 * // shift the offsets of later edits.
 * }</pre>
 */
public final class Rename {
  private Rename() {}

  /**
   * Rename the symbol at the given character offset.
   *
   * @param compilation the compilation to search
   * @param uri URI of the source containing the target symbol
   * @param offset zero-based character offset of the target symbol
   * @param newName the replacement identifier
   * @return one {@link TextEdit} per reference; empty if no symbol is present at the offset
   * @throws InvalidIdentifierException if {@code newName} is not a valid Java identifier or is a
   *     reserved word
   * @throws FindReferences.NoSuchSourceException if {@code uri} is not present in the compilation
   * @throws IllegalStateException if the compilation has no semantic model
   */
  public static ImmutableList<TextEdit> atPosition(
      Compilation compilation, URI uri, int offset, String newName) {
    validateIdentifier(newName);
    var target = FindReferences.resolveElement(compilation, uri, offset);
    if (target == null) {
      return ImmutableList.of();
    }
    return renameElements(compilation, expandRenameTargets(target), newName);
  }

  /**
   * Rename a specific element and all its references.
   *
   * @param compilation the compilation to search
   * @param target the element to rename
   * @param newName the replacement identifier
   * @return one {@link TextEdit} per reference
   * @throws InvalidIdentifierException if {@code newName} is not a valid Java identifier or is a
   *     reserved word
   * @throws IllegalStateException if the compilation has no semantic model
   */
  public static ImmutableList<TextEdit> forElement(
      Compilation compilation, Element target, String newName) {
    Objects.requireNonNull(target, "target");
    validateIdentifier(newName);
    return renameElements(compilation, expandRenameTargets(target), newName);
  }

  private static ImmutableList<TextEdit> renameElements(
      Compilation compilation, Set<Element> targets, String newName) {
    // De-dupe references — a class and its constructors both produce a range on the
    // class name in `new Foo()` sites.
    var seenRanges = new LinkedHashSet<Reference>();
    for (var element : targets) {
      for (var ref : FindReferences.forElement(compilation, element)) {
        seenRanges.add(ref);
      }
    }
    var edits = ImmutableList.<TextEdit>builder();
    for (var ref : seenRanges) {
      edits.add(new TextEdit(ref.uri(), ref.range(), newName));
    }
    return edits.build();
  }

  /**
   * Class and constructor declarations share their name in source, so a rename of one must include
   * the other.
   */
  private static Set<Element> expandRenameTargets(Element target) {
    var expanded = new LinkedHashSet<Element>();
    expanded.add(target);

    if (target instanceof TypeElement type) {
      // Renaming a class also renames its constructors.
      for (var member : type.getEnclosedElements()) {
        if (member.getKind() == ElementKind.CONSTRUCTOR) {
          expanded.add(member);
        }
      }
    } else if (target.getKind() == ElementKind.CONSTRUCTOR) {
      // Renaming a constructor also renames the enclosing class and siblings.
      var enclosing = target.getEnclosingElement();
      if (enclosing instanceof TypeElement typeElement) {
        expanded.add(typeElement);
        for (var member : typeElement.getEnclosedElements()) {
          if (member.getKind() == ElementKind.CONSTRUCTOR) {
            expanded.add(member);
          }
        }
      }
    }
    return expanded;
  }

  private static void validateIdentifier(String newName) {
    Objects.requireNonNull(newName, "newName");
    if (newName.isEmpty()) {
      throw new InvalidIdentifierException("New name must not be empty");
    }
    if (!Character.isJavaIdentifierStart(newName.charAt(0))) {
      throw new InvalidIdentifierException("Invalid Java identifier: '" + newName + "'");
    }
    for (var i = 1; i < newName.length(); i++) {
      if (!Character.isJavaIdentifierPart(newName.charAt(i))) {
        throw new InvalidIdentifierException("Invalid Java identifier: '" + newName + "'");
      }
    }
    if (RESERVED.contains(newName)) {
      throw new InvalidIdentifierException(
          "Reserved Java word cannot be used as an identifier: '" + newName + "'");
    }
  }

  /** Java reserved keywords and reserved literals — not usable as identifiers. */
  private static final Set<String> RESERVED =
      Set.of(
          // Keywords (JLS §3.9)
          "abstract",
          "assert",
          "boolean",
          "break",
          "byte",
          "case",
          "catch",
          "char",
          "class",
          "const",
          "continue",
          "default",
          "do",
          "double",
          "else",
          "enum",
          "extends",
          "final",
          "finally",
          "float",
          "for",
          "goto",
          "if",
          "implements",
          "import",
          "instanceof",
          "int",
          "interface",
          "long",
          "native",
          "new",
          "package",
          "private",
          "protected",
          "public",
          "return",
          "short",
          "static",
          "strictfp",
          "super",
          "switch",
          "synchronized",
          "this",
          "throw",
          "throws",
          "transient",
          "try",
          "void",
          "volatile",
          "while",
          // Reserved literals
          "true",
          "false",
          "null",
          // Reserved identifier (JLS §3.8)
          "_");

  /**
   * Thrown when a proposed rename identifier is not a valid Java identifier or is a reserved word.
   */
  public static final class InvalidIdentifierException extends RuntimeException {
    InvalidIdentifierException(String message) {
      super(message);
    }
  }
}
