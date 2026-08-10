package com.codepilot1c.core.edt.ast;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

/**
 * Behaviour of {@link SourceFilePathCandidates} — what a BSL {@code filePath} argument may mean.
 *
 * <p>Pinned around the live 2026-08-10 case: {@code bsl_object_context} on
 * {@code Document.CasinoCashflowTransactions} reported both modules as
 * {@code missing / File not found: src/Documents/CasinoCashflowTransactions/ObjectModule.bsl} while
 * {@code glob} listed the file at exactly that path. The aggregator prepended {@code src/} on top of
 * the gateway's own, so the lookup asked for {@code src/src/…} while the message printed the
 * single-prefixed path. Confirmed from both sides through the MCP endpoint: the unprefixed path
 * answered with 8 methods, the prefixed one produced the reported error verbatim.</p>
 */
public class SourceFilePathCandidatesTest {

    @Test
    public void aDocumentedPathIsResolvedUnderSrc() {
        assertEquals(
                List.of("src/Documents/CasinoCashflowTransactions/ObjectModule.bsl"),
                SourceFilePathCandidates.forFilePath("Documents/CasinoCashflowTransactions/ObjectModule.bsl"));
    }

    @Test
    public void anAlreadyPrefixedPathKeepsTheDocumentedReadingFirst() {
        // Order matters: whatever resolves today must keep resolving, so the doubled spelling is
        // still tried first and the pasted reading is only a fallback.
        assertEquals(
                List.of("src/src/Documents/X/ObjectModule.bsl", "src/Documents/X/ObjectModule.bsl"),
                SourceFilePathCandidates.forFilePath("src/Documents/X/ObjectModule.bsl"));
    }

    @Test
    public void aPathPastedFromGlobIsReachable() {
        // The whole point: glob prints src/-prefixed paths, and handing one over must not dead-end.
        assertTrue(SourceFilePathCandidates.forFilePath("src/CommonModules/Error/Module.bsl")
                .contains("src/CommonModules/Error/Module.bsl"));
    }

    @Test
    public void windowsSeparatorsAreAccepted() {
        assertEquals(
                List.of("src/CommonModules/Error/Module.bsl"),
                SourceFilePathCandidates.forFilePath("CommonModules\\Error\\Module.bsl"));
    }

    @Test
    public void aLeadingSlashDoesNotMakeItADifferentFile() {
        assertEquals(
                List.of("src/CommonModules/Error/Module.bsl"),
                SourceFilePathCandidates.forFilePath("/CommonModules/Error/Module.bsl"));
        assertEquals(
                List.of("src/src/CommonModules/Error/Module.bsl", "src/CommonModules/Error/Module.bsl"),
                SourceFilePathCandidates.forFilePath("/src/CommonModules/Error/Module.bsl"));
    }

    @Test
    public void nothingUsableYieldsNoCandidates() {
        assertTrue(SourceFilePathCandidates.forFilePath(null).isEmpty());
        assertTrue(SourceFilePathCandidates.forFilePath("   ").isEmpty());
        assertTrue(SourceFilePathCandidates.forFilePath("/").isEmpty());
    }

    @Test
    public void theSourceFolderNameAloneIsNotAPrefixToStrip() {
        // "src" names the folder, not a file inside it; there is no second reading of it.
        assertFalse(SourceFilePathCandidates.startsWithSourceFolder("src"));
        assertEquals(List.of("src/src"), SourceFilePathCandidates.forFilePath("src"));
    }

    @Test
    public void aFolderNamedLikeThePrefixIsNotMistakenForIt() {
        // "srcgen/…" merely starts with the same letters.
        assertFalse(SourceFilePathCandidates.startsWithSourceFolder("srcgen/X/Module.bsl"));
        assertEquals(
                List.of("src/srcgen/X/Module.bsl"),
                SourceFilePathCandidates.forFilePath("srcgen/X/Module.bsl"));
    }

    @Test
    public void theSourceFolderMatchIsCaseInsensitive() {
        assertTrue(SourceFilePathCandidates.startsWithSourceFolder("SRC/X/Module.bsl"));
        assertEquals(
                List.of("src/SRC/X/Module.bsl", "SRC/X/Module.bsl"),
                SourceFilePathCandidates.forFilePath("SRC/X/Module.bsl"));
    }
}
