package com.codepilot1c.core.edt.publication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behaviour of the post-op {@code LoadModule _1cws_module} repair on real conf text: input conf in,
 * output conf out (feedback 2026-10-05 …web-publication-remove-strips-loadmodule-publish-no-dedup).
 */
public class ApacheWsModuleLinesTest {

    private static final String BACKSLASH = "LoadModule _1cws_module \"C:\\Program Files\\1cv8\\8.3.27.1786\\bin\\wsap24.dll\""; //$NON-NLS-1$
    private static final String FORWARD = "LoadModule _1cws_module \"C:/Program Files/1cv8/8.3.27.1786/bin/wsap24.dll\""; //$NON-NLS-1$
    private static final String OTHER_VERSION = "LoadModule _1cws_module \"C:/Program Files/1cv8/8.3.25.1000/bin/wsap24.dll\""; //$NON-NLS-1$
    private static final String KEY_27 = ApacheWsModuleLines.modulePathKey("C:\\Program Files\\1cv8\\8.3.27.1786\\bin\\wsap24.dll"); //$NON-NLS-1$

    /** A per-stack httpd-1c.conf after EDT's remove of its only publication: module line gone. */
    private static final String STRIPPED_CONF = String.join("\r\n", //$NON-NLS-1$
            "ServerRoot \"C:/1C/Dudko/apache-stack-2\"", //$NON-NLS-1$
            "Listen 8082", //$NON-NLS-1$
            "LoadModule authz_core_module modules/mod_authz_core.so", //$NON-NLS-1$
            "LoadModule mime_module modules/mod_mime.so", //$NON-NLS-1$
            "", //$NON-NLS-1$
            "ServerName localhost:8082", //$NON-NLS-1$
            "") ; //$NON-NLS-1$

    @Test
    public void removeOfLastPublicationRestoresTheModuleLineAfterTheLoadModuleBlock() {
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(STRIPPED_CONF, KEY_27, BACKSLASH);

        assertTrue(outcome.changed());
        assertTrue(outcome.reinserted());
        assertEquals(String.join("\r\n", //$NON-NLS-1$
                "ServerRoot \"C:/1C/Dudko/apache-stack-2\"", //$NON-NLS-1$
                "Listen 8082", //$NON-NLS-1$
                "LoadModule authz_core_module modules/mod_authz_core.so", //$NON-NLS-1$
                "LoadModule mime_module modules/mod_mime.so", //$NON-NLS-1$
                BACKSLASH,
                "", //$NON-NLS-1$
                "ServerName localhost:8082", //$NON-NLS-1$
                ""), outcome.text()); //$NON-NLS-1$
        assertNotNull(outcome.note());
    }

    @Test
    public void restoredLineGoesBeforeAPublicationThatUsesTheModule() {
        String conf = String.join("\n", //$NON-NLS-1$
                "LoadModule mime_module modules/mod_mime.so", //$NON-NLS-1$
                "# 1c publication", //$NON-NLS-1$
                "Alias \"/other\" \"C:/pub/other/\"", //$NON-NLS-1$
                "<Directory \"C:/pub/other/\">", //$NON-NLS-1$
                "\tManagedApplicationDescriptor \"C:/pub/other/default.vrd\"", //$NON-NLS-1$
                "</Directory>", //$NON-NLS-1$
                "LoadModule late_module modules/mod_late.so"); //$NON-NLS-1$

        String text = ApacheWsModuleLines.normalize(conf, null, FORWARD).text();

        String[] lines = text.split("\n"); //$NON-NLS-1$
        assertEquals(FORWARD, lines[1]);
        assertEquals("LoadModule late_module modules/mod_late.so", lines[lines.length - 1]); //$NON-NLS-1$
        assertFalse("no trailing newline invented", text.endsWith("\n")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void confWithoutAnyLoadModuleGetsTheLineAtTheTop() {
        String text = ApacheWsModuleLines.normalize("Listen 8080\n", null, FORWARD).text(); //$NON-NLS-1$
        assertEquals(FORWARD + "\nListen 8080\n", text); //$NON-NLS-1$
    }

    @Test
    public void nothingToRestoreWhenTheConfHadNoModuleBeforeRemove() {
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(STRIPPED_CONF, null, null);
        assertFalse(outcome.changed());
        assertSame(STRIPPED_CONF, outcome.text());
        assertNull(outcome.note());
    }

    @Test
    public void singleModuleLineIsLeftByteIdentical() {
        String conf = "LoadModule mime_module modules/mod_mime.so\r\n" + BACKSLASH + "\r\nListen 80\r\n"; //$NON-NLS-1$ //$NON-NLS-2$
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(conf, KEY_27, FORWARD);
        assertFalse(outcome.changed());
        assertSame(conf, outcome.text());
    }

    @Test
    public void stack3BackslashAndForwardSlashDuplicatesCollapseToOne() {
        // apache-stack-3: line 10 backslash form (pre-existing), line 26 forward-slash form (publish).
        String conf = String.join("\r\n", //$NON-NLS-1$
                "LoadModule mime_module modules/mod_mime.so", //$NON-NLS-1$
                BACKSLASH,
                "LoadModule rewrite_module modules/mod_rewrite.so", //$NON-NLS-1$
                "  " + FORWARD.replace("_1cws_module", "_1CWS_MODULE") + "  ", //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
                "Listen 8083", //$NON-NLS-1$
                ""); //$NON-NLS-1$

        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(conf, KEY_27, FORWARD);

        assertTrue(outcome.changed());
        assertEquals(1, outcome.duplicatesRemoved());
        assertEquals(String.join("\r\n", //$NON-NLS-1$
                "LoadModule mime_module modules/mod_mime.so", //$NON-NLS-1$
                BACKSLASH,
                "LoadModule rewrite_module modules/mod_rewrite.so", //$NON-NLS-1$
                "Listen 8083", //$NON-NLS-1$
                ""), outcome.text()); //$NON-NLS-1$
    }

    @Test
    public void duplicatesOfTheSameModuleCollapseEvenWithoutAPreference() {
        String conf = FORWARD + "\n" + BACKSLASH + "\n" //$NON-NLS-1$ //$NON-NLS-2$
                + "LoadModule _1cws_module C:/Program\\ Files/1cv8/8.3.27.1786/bin//wsap24.dll\n"; //$NON-NLS-1$
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(conf, null, null);
        // The unquoted, escaped-space variant is a different string; only the two quoted ones are the same module.
        assertFalse(outcome.changed());
        assertNotNull(outcome.note());

        String sameTwice = FORWARD + "\n" + BACKSLASH + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals(FORWARD + "\n", ApacheWsModuleLines.normalize(sameTwice, null, null).text()); //$NON-NLS-1$
    }

    @Test
    public void afterPublishTheJustPublishedModuleWinsOverAStaleOne() {
        String conf = OTHER_VERSION + "\n" + FORWARD + "\nListen 80\n"; //$NON-NLS-1$ //$NON-NLS-2$
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(conf, KEY_27, FORWARD);
        assertEquals(FORWARD + "\nListen 80\n", outcome.text()); //$NON-NLS-1$
    }

    @Test
    public void twoDifferentModulesWithoutAPreferenceAreNeverGuessedAt() {
        String conf = OTHER_VERSION + "\n" + FORWARD + "\n"; //$NON-NLS-1$ //$NON-NLS-2$
        ApacheWsModuleLines.Outcome outcome = ApacheWsModuleLines.normalize(conf, null, null);
        assertFalse(outcome.changed());
        assertSame(conf, outcome.text());
        assertTrue(outcome.note().contains("DIFFERENT")); //$NON-NLS-1$
    }

    @Test
    public void commentedModuleLineDoesNotCountAndIsKept() {
        String conf = "#" + FORWARD + "\nListen 80\n"; //$NON-NLS-1$ //$NON-NLS-2$
        String text = ApacheWsModuleLines.normalize(conf, null, BACKSLASH).text();
        assertEquals(BACKSLASH + "\n#" + FORWARD + "\nListen 80\n", text); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void pathVariantsShareOneKey() {
        String a = ApacheWsModuleLines.modulePathKey("\"C:\\1cv8\\bin\\wsap24.dll\""); //$NON-NLS-1$
        assertEquals(a, ApacheWsModuleLines.modulePathKey("C:/1cv8/bin/wsap24.dll")); //$NON-NLS-1$
        assertEquals(a, ApacheWsModuleLines.modulePathKey("'c:/1cv8//BIN/wsap24.dll'")); //$NON-NLS-1$
        assertFalse(a.equals(ApacheWsModuleLines.modulePathKey("C:/1cv8/bin/wsap22.dll"))); //$NON-NLS-1$
    }

    @Test
    public void firstModuleLineAndLineShape() {
        assertEquals(BACKSLASH, ApacheWsModuleLines.firstModuleLine("Listen 80\r\n" + BACKSLASH + "\r\n" + FORWARD)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(ApacheWsModuleLines.firstModuleLine(STRIPPED_CONF));
        assertEquals(FORWARD, ApacheWsModuleLines.moduleLineFor("C:\\Program Files\\1cv8\\8.3.27.1786\\bin\\wsap24.dll")); //$NON-NLS-1$
    }
}
