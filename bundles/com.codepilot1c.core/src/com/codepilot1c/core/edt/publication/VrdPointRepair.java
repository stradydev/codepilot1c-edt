package com.codepilot1c.core.edt.publication;

import java.io.StringReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import com._1c.g5.v8.dt.platform.services.model.InfobasePublication;

/**
 * Guard and repair for the {@code default.vrd} EDT's {@code InfobasePublicationXmlWriter} produces.
 *
 * <p>EDT bug (decompile of services.core 21.0 {@code InfobasePublicationXmlWriter.write}): the writer
 * chooses an EMPTY {@code <point/>} when debug, data separators, openId/openIdConnect, pool, ws,
 * httpServices and exitURL are all absent <em>and {@code standardOdata != null}</em> — the OData test is
 * inverted. It then writes {@code <standardOdata/>} (and {@code <analytics/>}) after the already
 * closed point, i.e. as a second document root. The platform answers every request with HTTP 500
 * "Extra content at the end of the document" (feedback 2026-09-21 …yaxunit-extension-full-push, publish
 * with {@code enable_standard_odata=true}).</p>
 *
 * <p>{@link #selfClosesPointWithChildren} predicts that branch so the caller can avoid it (a default
 * {@code Pool} is not serialized but flips the writer onto the open-element branch);
 * {@link #nestRootSiblingsIntoPoint} repairs a vrd that was already written that way.</p>
 */
final class VrdPointRepair {

    /** A self-closed {@code <point …/>} start tag; attribute values are matched as quoted strings. */
    private static final Pattern SELF_CLOSED_POINT = Pattern.compile(
            "<point\\b((?:\\s+[^\\s=/>]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'))*)\\s*/>"); //$NON-NLS-1$

    private VrdPointRepair() {
    }

    /**
     * True when EDT's writer would emit {@code <point/>} self-closed and then write {@code standardOdata}
     * after it — the exact condition of {@code InfobasePublicationXmlWriter.write}, inversion included.
     */
    static boolean selfClosesPointWithChildren(InfobasePublication publication) {
        return publication.getStandardOdata() != null
                && publication.getDebug() == null
                && (publication.getDataSeparators() == null || publication.getDataSeparators().isEmpty())
                && publication.getOpenId() == null
                && publication.getOpenIdConnect() == null
                && publication.getPool() == null
                && publication.getWebServices() == null
                && publication.getHttpServices() == null
                && publication.getExitUrl() == null;
    }

    /**
     * Returns {@code vrd} with every element that follows a self-closed {@code <point/>} moved inside
     * it, or {@code null} when there is nothing to repair (already well-formed, no self-closed point, or
     * nothing but whitespace/comments after it) or when the repaired text would still not be
     * well-formed — the caller then leaves the file alone.
     */
    static String nestRootSiblingsIntoPoint(String vrd) {
        if (vrd == null || isWellFormed(vrd)) {
            return null;
        }
        Matcher matcher = SELF_CLOSED_POINT.matcher(vrd);
        if (!matcher.find()) {
            return null;
        }
        String head = vrd.substring(0, matcher.start());
        String tail = vrd.substring(matcher.end());
        if (!tail.contains("<") || tail.replaceAll("(?s)<!--.*?-->", "").isBlank()) { //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            return null;
        }
        // Keep the tail's own trailing whitespace after the new closing tag, so the file still ends
        // the way the writer ended it.
        int contentEnd = tail.length();
        while (contentEnd > 0 && Character.isWhitespace(tail.charAt(contentEnd - 1))) {
            contentEnd--;
        }
        String eol = vrd.contains("\r\n") ? "\r\n" : "\n"; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        String repaired = head + "<point" + matcher.group(1) + ">" //$NON-NLS-1$ //$NON-NLS-2$
                + tail.substring(0, contentEnd) + eol + "</point>" + tail.substring(contentEnd); //$NON-NLS-1$
        return isWellFormed(repaired) ? repaired : null;
    }

    static boolean isWellFormed(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); //$NON-NLS-1$
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            String body = xml.startsWith("\uFEFF") ? xml.substring(1) : xml; //$NON-NLS-1$
            builder.parse(new InputSource(new StringReader(body)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
