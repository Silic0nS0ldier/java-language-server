package me.djmm.java.code_analysis;

/**
 * Tracks how far a compilation progressed.
 */
public enum CompilationPhase {
    /** Sources were parsed into syntax trees. */
    PARSED,
    /** Full semantic analysis was performed (type checking, name resolution, etc.). */
    ANALYZED
}
