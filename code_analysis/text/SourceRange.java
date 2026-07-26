package me.djmm.java.code_analysis.text;

/**
 * A location in a source file, expressed as a byte offset range plus 1-based line/column
 * coordinates.
 *
 * <p>Offsets are inclusive-start, exclusive-end (matching javac's {@code SourcePositions}
 * convention). Line and column values are 1-based to match common editor conventions.
 *
 * @param startOffset zero-based character offset where the reference begins (inclusive)
 * @param endOffset zero-based character offset where the reference ends (exclusive)
 * @param startLine 1-based line number of the start position
 * @param startColumn 1-based column number of the start position
 * @param endLine 1-based line number of the end position
 * @param endColumn 1-based column number of the end position
 */
public record SourceRange(
    int startOffset, int endOffset, int startLine, int startColumn, int endLine, int endColumn) {
  public SourceRange {
    if (startOffset < 0 || endOffset < startOffset) {
      throw new IllegalArgumentException(
          "Invalid offsets: start=" + startOffset + " end=" + endOffset);
    }
  }
}
