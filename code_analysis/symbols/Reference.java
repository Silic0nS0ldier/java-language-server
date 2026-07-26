package me.djmm.java.code_analysis.symbols;

import java.net.URI;
import me.djmm.java.code_analysis.text.SourceRange;

/**
 * A single reference to a symbol.
 *
 * @param uri    URI of the source file containing the reference
 * @param range  location of the reference within the file
 * @param kind   whether this is a use of the symbol or its declaration
 */
public record Reference(URI uri, SourceRange range, Kind kind) {
    public enum Kind {
        /** The declaration of the symbol (e.g. {@code int x = 0;}). */
        DECLARATION,
        /** A use of the symbol (e.g. {@code x + 1}). */
        USE
    }
}
