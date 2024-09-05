package org.javacs;

import java.util.logging.LogManager;
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
import org.junit.runners.Suite.SuiteClasses;
import org.javacs.lsp.LanguageServerTest;
import org.javacs.lsp.LspTest;
import org.javacs.rewrite.RewriteTest;

@RunWith(Suite.class)
@SuiteClasses({
    ArtifactTest.class,
    ClassesTest.class,
    CodeActionTest.class,
    CodeLensTest.class,
    CompletionsScopesTest.class,
    CompletionsTest.class,
    FileStoreTest.class,
    FindReferencesTest.class,
    GotoTest.class,
    HoverTest.class,
    IncrementalCompileTest.class,
    //InferBazelConfigTest.class,
    InferConfigTest.class,
    JavaDebugServerTest.class,
    JavaLanguageServerTest.class,
    MarkdownHelperTest.class,
    SearchTest.class,
    SemanticColorsTest.class,
    SignatureHelpTest.class,
    SourceFileManagerTest.class,
    StringSearchTest.class,
    WarningsTest.class,
    LanguageServerTest.class,
    LspTest.class,
    RewriteTest.class,
})
public class AllTests {
    static {
        LogManager.getLogManager().reset();
        org.javacs.Main.showMiscLogging = false;
    }
}
