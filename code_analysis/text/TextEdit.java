package me.djmm.java.code_analysis.text;

import java.net.URI;
import java.util.Objects;

/**
 * A single text edit — replace the source content in {@link #range()} with {@link #replacement()}.
 *
 * <p>Edits are independent values; a caller applies them by grouping by {@link #uri()} and
 * rewriting each file. When applying multiple edits to the same file, sort by {@code
 * range.startOffset()} descending so earlier edits do not shift the offsets of later edits.
 *
 * @param uri URI of the file to modify
 * @param range source range to replace (existing content between the offsets is discarded)
 * @param replacement the new text to insert in place of the range (may be empty)
 */
public record TextEdit(URI uri, SourceRange range, String replacement) {
  public TextEdit {
    Objects.requireNonNull(uri, "uri");
    Objects.requireNonNull(range, "range");
    Objects.requireNonNull(replacement, "replacement");
  }
}
