/*
 * Copyright (c) 2024 Example
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Tests for {@link GrepFileFilter} — the corpus rule behind {@code grep}'s silent zeros.
 */
public class GrepFileFilterTest {

    @Test
    public void metadataSourcesAreInTheDefaultCorpus() {
        // The reported miss: an item name lives in a .form and used to be unreachable by default.
        assertTrue(GrepFileFilter.matches("Form.form", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("Catalog.mdo", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("Scheme.dcs", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("Rights.rights", null)); //$NON-NLS-1$
    }

    @Test
    public void codeSourcesStayInTheDefaultCorpus() {
        assertTrue(GrepFileFilter.matches("ObjectModule.bsl", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("script.os", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("plugin.xml", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("GrepTool.java", null)); //$NON-NLS-1$
    }

    @Test
    public void otherExtensionsStayOutOfTheDefaultCorpus() {
        assertFalse(GrepFileFilter.matches("README.md", null)); //$NON-NLS-1$
        assertFalse(GrepFileFilter.matches("package.json", null)); //$NON-NLS-1$
        assertFalse(GrepFileFilter.matches("notes.txt", null)); //$NON-NLS-1$
        assertFalse(GrepFileFilter.matches("noextension", null)); //$NON-NLS-1$
    }

    @Test
    public void defaultCorpusIsCaseInsensitive() {
        assertTrue(GrepFileFilter.matches("MODULE.BSL", null)); //$NON-NLS-1$
        assertTrue(GrepFileFilter.matches("Form.FORM", "")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void explicitGlobOverridesTheDefaultCorpus() {
        assertTrue(GrepFileFilter.matches("README.md", "*.md")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GrepFileFilter.matches("ObjectModule.bsl", "*.form")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void pathShapedGlobMatchesOnItsLastSegment() {
        // The filter only ever sees a bare file name, so a full match against "**/*.bsl" rejected
        // every file and produced a silent zero.
        assertTrue(GrepFileFilter.matches("ObjectModule.bsl", "**/*.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GrepFileFilter.matches("Form.form", "src/**/*.form")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GrepFileFilter.matches("Form.form", "**/*.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void commaSeparatedListMatchesAnyOfTheGlobs() {
        assertTrue(GrepFileFilter.matches("ObjectModule.bsl", "*.bsl,*.mdo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GrepFileFilter.matches("Catalog.mdo", "*.bsl, *.mdo")); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(GrepFileFilter.matches("Form.form", "*.bsl,*.mdo")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void globIsCaseInsensitiveAndSupportsSingleCharWildcard() {
        assertTrue(GrepFileFilter.matches("Module.BSL", "*.bsl")); //$NON-NLS-1$ //$NON-NLS-2$
        assertTrue(GrepFileFilter.matches("Form1.form", "Form?.form")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void nullNameNeverMatches() {
        assertFalse(GrepFileFilter.matches(null, null));
        assertFalse(GrepFileFilter.matches(null, "*.bsl")); //$NON-NLS-1$
    }

    @Test
    public void describedCorpusNamesTheMetadataSources() {
        String corpus = GrepFileFilter.describeDefaultCorpus();
        assertTrue(corpus.contains("*.bsl")); //$NON-NLS-1$
        assertTrue(corpus.contains("*.form")); //$NON-NLS-1$
        assertTrue(corpus.contains("*.mdo")); //$NON-NLS-1$
    }
}
