package com.codepilot1c.core.edt.publication;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.Test;
import org.w3c.dom.Element;

import com._1c.g5.v8.dt.platform.services.model.InfobasePublication;
import com._1c.g5.v8.dt.platform.services.model.OData;
import com._1c.g5.v8.dt.platform.services.model.Pool;

/**
 * The {@code default.vrd} EDT writes for {@code enable_standard_odata=true}: {@code <point/>} self-closed
 * and {@code <standardOdata/>} as a second root (feedback 2026-09-21 …yaxunit-extension-full-push,
 * "Separate, NEW bug"). Verified on vrd text in / vrd text out, plus the model guard that keeps EDT's
 * writer off that branch and the probe body excerpt that made the platform's parser error visible.
 */
public class VrdPointRepairTest {

    private static final String NS = "http://v8.1c.ru/8.2/virtual-resource-system"; //$NON-NLS-1$

    /** Shape of InfobasePublicationXmlWriter's output for a publication carrying only standardOdata. */
    private static final String BROKEN_VRD = String.join("\n", //$NON-NLS-1$
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>", //$NON-NLS-1$
            "<point xmlns=\"" + NS + "\"", //$NON-NLS-1$ //$NON-NLS-2$
            "\t\txmlns:xs=\"http://www.w3.org/2001/XMLSchema\"", //$NON-NLS-1$
            "\t\txmlns:core=\"http://v8.1c.ru/8.1/data/core\"", //$NON-NLS-1$
            "\t\tbase=\"/agent-current\"", //$NON-NLS-1$
            "\t\tib=\"File=&quot;C:\\1C\\Dudko\\db\\Branches\\BF-13878&quot;;\"", //$NON-NLS-1$
            "\t\tenable=\"true\"", //$NON-NLS-1$
            "\t\tenableStandardOData=\"true\"/>", //$NON-NLS-1$
            "<standardOdata enable=\"true\"", //$NON-NLS-1$
            "\t\treuseSessions=\"autouse\"", //$NON-NLS-1$
            "\t\tsessionMaxAge=\"20\"", //$NON-NLS-1$
            "\t\tpoolSize=\"10\"", //$NON-NLS-1$
            "\t\tpoolTimeout=\"5\"/>", //$NON-NLS-1$
            ""); //$NON-NLS-1$

    @Test
    public void brokenVrdIsNotWellFormedAsWritten() {
        assertFalse(VrdPointRepair.isWellFormed(BROKEN_VRD));
    }

    @Test
    public void standardOdataSiblingIsNestedInsidePoint() throws Exception {
        String repaired = VrdPointRepair.nestRootSiblingsIntoPoint(BROKEN_VRD);

        assertNotNull(repaired);
        assertTrue(repaired, repaired.contains("enableStandardOData=\"true\">\n<standardOdata")); //$NON-NLS-1$
        assertTrue(repaired, repaired.endsWith("poolTimeout=\"5\"/>\n</point>\n")); //$NON-NLS-1$
        Element root = parse(repaired);
        assertEquals("point", root.getLocalName()); //$NON-NLS-1$
        assertEquals("true", root.getAttribute("enableStandardOData")); //$NON-NLS-1$ //$NON-NLS-2$
        Element odata = (Element) root.getElementsByTagNameNS(NS, "standardOdata").item(0); //$NON-NLS-1$
        assertNotNull("standardOdata must be a child of point in the vrd namespace", odata); //$NON-NLS-1$
        assertEquals(root, odata.getParentNode());
        assertEquals("autouse", odata.getAttribute("reuseSessions")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void analyticsSiblingIsNestedToo() throws Exception {
        String vrd = BROKEN_VRD + "<analytics enable=\"true\"/>\n"; //$NON-NLS-1$
        Element root = parse(VrdPointRepair.nestRootSiblingsIntoPoint(vrd));
        assertEquals(1, root.getElementsByTagNameNS(NS, "standardOdata").getLength()); //$NON-NLS-1$
        assertEquals(1, root.getElementsByTagNameNS(NS, "analytics").getLength()); //$NON-NLS-1$
    }

    @Test
    public void crlfAndAQuotedSlashGreaterThanInAnAttributeSurvive() throws Exception {
        String vrd = BROKEN_VRD.replace("\n", "\r\n") //$NON-NLS-1$ //$NON-NLS-2$
                .replace("base=\"/agent-current\"", "base=\"/a/>b\""); //$NON-NLS-1$ //$NON-NLS-2$
        String repaired = VrdPointRepair.nestRootSiblingsIntoPoint(vrd);
        assertNotNull(repaired);
        assertTrue(repaired.endsWith("\r\n</point>\r\n")); //$NON-NLS-1$
        assertEquals("/a/>b", parse(repaired).getAttribute("base")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void wellFormedVrdIsLeftAlone() {
        String good = BROKEN_VRD.replace("enableStandardOData=\"true\"/>", "enableStandardOData=\"true\">") //$NON-NLS-1$ //$NON-NLS-2$
                + "</point>\n"; //$NON-NLS-1$
        assertTrue(VrdPointRepair.isWellFormed(good));
        assertNull(VrdPointRepair.nestRootSiblingsIntoPoint(good));
    }

    @Test
    public void bareSelfClosedPointIsAlreadyValid() {
        String bare = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<point xmlns=\"" + NS //$NON-NLS-1$
                + "\" base=\"/x\" ib=\"File=&quot;C:\\db&quot;;\"/>\n"; //$NON-NLS-1$
        assertNull(VrdPointRepair.nestRootSiblingsIntoPoint(bare));
    }

    @Test
    public void unrepairableGarbageIsNotTouched() {
        assertNull(VrdPointRepair.nestRootSiblingsIntoPoint("<point a=\"1\"/><x>")); //$NON-NLS-1$
        assertNull(VrdPointRepair.nestRootSiblingsIntoPoint("not xml at all")); //$NON-NLS-1$
    }

    // -- guard ------------------------------------------------------------------------------

    @Test
    public void guardFiresForAPublicationCarryingOnlyStandardOdata() {
        Map<String, Object> values = new HashMap<>();
        values.put("getStandardOdata", proxy(OData.class)); //$NON-NLS-1$
        assertTrue(VrdPointRepair.selfClosesPointWithChildren(publication(values)));
    }

    @Test
    public void guardStaysQuietOnceAnyOtherChildIsPresent() {
        Map<String, Object> values = new HashMap<>();
        values.put("getStandardOdata", proxy(OData.class)); //$NON-NLS-1$
        values.put("getPool", proxy(Pool.class)); //$NON-NLS-1$
        assertFalse(VrdPointRepair.selfClosesPointWithChildren(publication(values)));

        values.remove("getPool"); //$NON-NLS-1$
        values.put("getExitUrl", "https://exit"); //$NON-NLS-1$ //$NON-NLS-2$
        assertFalse(VrdPointRepair.selfClosesPointWithChildren(publication(values)));
    }

    @Test
    public void guardStaysQuietWithoutStandardOdata() {
        assertFalse(VrdPointRepair.selfClosesPointWithChildren(publication(new HashMap<>())));
    }

    // -- probe body -------------------------------------------------------------------------

    @Test
    public void probeBodyExcerptHonoursTheCharsetAndTheCap() {
        String platformError = "Ошибка разбора XML: Extra content at the end of the document"; //$NON-NLS-1$
        Charset cp1251 = Charset.forName("windows-1251"); //$NON-NLS-1$
        assertEquals(platformError, EdtWebPublicationService.bodyExcerpt(
                ("  " + platformError + "\r\n").getBytes(cp1251), "text/plain; charset=windows-1251", 1000)); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        assertEquals(platformError, EdtWebPublicationService.bodyExcerpt(
                platformError.getBytes(StandardCharsets.UTF_8), null, 1000));
        assertEquals("abcde... [truncated]", //$NON-NLS-1$
                EdtWebPublicationService.bodyExcerpt("abcdefgh".getBytes(StandardCharsets.UTF_8), "text/html", 5)); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull(EdtWebPublicationService.bodyExcerpt(new byte[0], null, 10));
        assertNull(EdtWebPublicationService.bodyExcerpt(" \n".getBytes(StandardCharsets.UTF_8), null, 10)); //$NON-NLS-1$
    }

    // -- helpers ----------------------------------------------------------------------------

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }

    private static InfobasePublication publication(Map<String, Object> values) {
        InvocationHandler handler = (Object p, Method method, Object[] args) -> {
            if (values.containsKey(method.getName())) {
                return values.get(method.getName());
            }
            return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        };
        return (InfobasePublication) Proxy.newProxyInstance(VrdPointRepairTest.class.getClassLoader(),
                new Class<?>[] {InfobasePublication.class}, handler);
    }

    private static <T> T proxy(Class<T> type) {
        InvocationHandler handler = (Object p, Method method, Object[] args) ->
                method.getReturnType() == boolean.class ? Boolean.FALSE : null;
        return type.cast(Proxy.newProxyInstance(VrdPointRepairTest.class.getClassLoader(),
                new Class<?>[] {type}, handler));
    }
}
