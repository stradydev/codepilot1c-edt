package com.codepilot1c.core.edt.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com._1c.g5.v8.dt.metadata.mdclass.Catalog;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPMethod;
import com._1c.g5.v8.dt.metadata.mdclass.HTTPService;
import com._1c.g5.v8.dt.metadata.mdclass.MdClassFactory;
import com._1c.g5.v8.dt.metadata.mdclass.MdObject;
import com._1c.g5.v8.dt.metadata.mdclass.URLTemplate;

/**
 * Behavioural regression for {@code add_metadata_child} over {@code HTTPService.urlTemplates} and
 * the {@code URLTemplate.methods} nested under them.
 *
 * <p>Background: codepilot1c-feedback
 * {@code 2026-09-11-add-metadata-child-cannot-create-httpservice-urltemplate}. Four structurally
 * different mechanisms were probed live and all four refused, each naming the same root:
 * {@link MetadataChildKind} is a closed enum and carried neither {@code URLTemplate} nor
 * {@code Method}, so an HTTP service could be created but never given a single route. The platform
 * has always supported the shape — the base configuration ships
 * {@code HTTPService.Callback} with a parameterised {@code /{id}} template.</p>
 *
 * <p>The tests below drive the real create path
 * ({@link EdtMetadataService#createGenericChildForResolvedParent}) over factory-built EMF objects,
 * so they see what actually lands in the model rather than what the source says it does. Three
 * things are pinned: the child is created at all, it is attached to the RIGHT containment feature,
 * and the supplied properties reach it instead of being silently dropped — which is exactly how the
 * sibling {@code Command} gap behaved before BF-12936.</p>
 */
public class HttpServiceRouteChildTest {

    private static final MdClassFactory FACTORY = MdClassFactory.eINSTANCE;

    private final EdtMetadataService service = new EdtMetadataService();

    // --- URLTemplate under an HTTPService ------------------------------------------------------

    @Test
    public void urlTemplateIsCreatedUnderTheServiceCarryingItsTemplate() {
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$

        String fqn = create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", //$NON-NLS-1$
                "V1Whoami", Map.of("template", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("HTTPService.MCPApi.URLTemplate.V1Whoami", fqn); //$NON-NLS-1$
        assertEquals("the template must land in urlTemplates, not in any other containment list", //$NON-NLS-1$
                1, parent.getUrlTemplates().size());
        URLTemplate created = parent.getUrlTemplates().get(0);
        assertEquals("V1Whoami", created.getName()); //$NON-NLS-1$
        assertEquals("the template pattern must reach the model, not be dropped on the floor", //$NON-NLS-1$
                "/v1/whoami", created.getTemplate()); //$NON-NLS-1$
        assertNotNull("every created MdObject needs a uuid or the .mdo is unloadable", //$NON-NLS-1$
                created.getUuid());
    }

    @Test
    public void aParameterisedTemplateIsStoredVerbatim() {
        // The live precedent is HTTPService.Callback with /{id}; the spec case is /v1/tools/{name}.
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$

        create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", //$NON-NLS-1$
                "V1ToolCall", Map.of("template", "/v1/tools/{name}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals("/v1/tools/{name}", parent.getUrlTemplates().get(0).getTemplate()); //$NON-NLS-1$
    }

    @Test
    public void severalTemplatesCanBeCreatedInOneBatchCall() {
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$

        List<Map<String, Object>> children = new ArrayList<>();
        children.add(childEntry("V1Whoami", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$
        children.add(childEntry("V1Tools", "/v1/tools")); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("children", children); //$NON-NLS-1$

        create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", null, properties); //$NON-NLS-1$

        assertEquals(2, parent.getUrlTemplates().size());
        assertEquals("/v1/whoami", parent.getUrlTemplates().get(0).getTemplate()); //$NON-NLS-1$
        assertEquals("/v1/tools", parent.getUrlTemplates().get(1).getTemplate()); //$NON-NLS-1$
    }

    // --- Method under an existing URLTemplate --------------------------------------------------

    @Test
    public void methodIsCreatedUnderTheTemplateNotUnderTheService() {
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$
        create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", //$NON-NLS-1$
                "V1ToolCall", Map.of("template", "/v1/tools/{name}")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        URLTemplate template = parent.getUrlTemplates().get(0);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("httpMethod", "POST"); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("handler", "V1ToolCallPost"); //$NON-NLS-1$ //$NON-NLS-2$
        String fqn = create(template, MetadataChildKind.METHOD,
                "HTTPService.MCPApi.URLTemplate.V1ToolCall", "Post", properties); //$NON-NLS-1$ //$NON-NLS-2$

        assertEquals("HTTPService.MCPApi.URLTemplate.V1ToolCall.Method.Post", fqn); //$NON-NLS-1$
        assertEquals(1, template.getMethods().size());
        com._1c.g5.v8.dt.metadata.mdclass.Method created = template.getMethods().get(0);
        assertEquals("Post", created.getName()); //$NON-NLS-1$
        assertEquals("the http verb must reach the model", HTTPMethod.POST, created.getHttpMethod()); //$NON-NLS-1$
        assertEquals("the handler is the whole point of the method", //$NON-NLS-1$
                "V1ToolCallPost", created.getHandler()); //$NON-NLS-1$
        assertNotNull(created.getUuid());
    }

    @Test
    public void anOmittedHttpMethodLeavesThePlatformDefault() {
        // The owner-facing table in the feedback note says "не задавать — по умолчанию GET" for the
        // read endpoints; pin that the platform default really is GET so the advice stays true.
        URLTemplate template = FACTORY.createURLTemplate();
        template.setName("V1Whoami"); //$NON-NLS-1$

        create(template, MetadataChildKind.METHOD, "HTTPService.MCPApi.URLTemplate.V1Whoami", //$NON-NLS-1$
                "Get", Map.of("handler", "V1WhoamiGet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertEquals(HTTPMethod.GET, template.getMethods().get(0).getHttpMethod());
        assertEquals("V1WhoamiGet", template.getMethods().get(0).getHandler()); //$NON-NLS-1$
    }

    // --- no silent drops -----------------------------------------------------------------------

    @Test
    public void anUnknownPropertyOnARouteChildFailsLoud() {
        // A dropped property is the failure mode this whole change exists to avoid: the object
        // would be written carrying nothing but its name and the caller would be told "success".
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$
        try {
            create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", //$NON-NLS-1$
                    "V1Whoami", Map.of("urlPattern", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a misspelled property must be refused, not swallowed"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_CHANGE, e.getCode());
            assertTrue("the message must name the offending field:\n" + e.getMessage(), //$NON-NLS-1$
                    e.getMessage().contains("urlPattern")); //$NON-NLS-1$
        }
    }

    @Test
    public void aUrlTemplateIsRefusedUnderAnOwnerThatHasNoSuchCollection() {
        Catalog parent = FACTORY.createCatalog();
        parent.setName("Контрагенты"); //$NON-NLS-1$
        try {
            create(parent, MetadataChildKind.URL_TEMPLATE, "Catalog.Контрагенты", //$NON-NLS-1$
                    "V1Whoami", Map.of("template", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
            fail("a catalog has no url templates — this must not be quietly parked somewhere"); //$NON-NLS-1$
        } catch (MetadataOperationException e) {
            assertEquals(MetadataOperationCode.INVALID_METADATA_KIND, e.getCode());
        }
    }

    // --- nested FQN addressing ------------------------------------------------------------------

    @Test
    public void theNestedFqnMarkersResolveTheTemplateAndTheMethod() {
        // parent_fqn="HTTPService.MCPApi.URLTemplate.V1Whoami" is how a Method is addressed, and
        // the same walk backs edt_metadata_details and the post-commit verify.
        HTTPService parent = httpService("MCPApi"); //$NON-NLS-1$
        create(parent, MetadataChildKind.URL_TEMPLATE, "HTTPService.MCPApi", //$NON-NLS-1$
                "V1Whoami", Map.of("template", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        URLTemplate template = parent.getUrlTemplates().get(0);
        create(template, MetadataChildKind.METHOD, "HTTPService.MCPApi.URLTemplate.V1Whoami", //$NON-NLS-1$
                "Get", Map.of("handler", "V1WhoamiGet")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$

        assertSame("URLTemplate marker must resolve the template", //$NON-NLS-1$
                template, service.findNestedChild(parent, "URLTemplate", "V1Whoami")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the marker is matched case-insensitively", //$NON-NLS-1$
                template, service.findNestedChild(parent, "urltemplate", "v1whoami")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame("the collection name is a marker too", //$NON-NLS-1$
                template, service.findNestedChild(parent, "urlTemplates", "V1Whoami")); //$NON-NLS-1$ //$NON-NLS-2$
        assertSame(template.getMethods().get(0),
                service.findNestedChild(template, "Method", "Get")); //$NON-NLS-1$ //$NON-NLS-2$
        assertNull("an unknown child must answer null, not the first thing in the list", //$NON-NLS-1$
                service.findNestedChild(parent, "URLTemplate", "V1Missing")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    // --- the gate the feedback note actually hit -------------------------------------------------

    @Test
    public void theClosedKindEnumNowAdmitsBothRouteKinds() {
        assertEquals(MetadataChildKind.URL_TEMPLATE, MetadataChildKind.fromString("URLTemplate")); //$NON-NLS-1$
        assertEquals(MetadataChildKind.URL_TEMPLATE, MetadataChildKind.fromString("url_template")); //$NON-NLS-1$
        assertEquals(MetadataChildKind.URL_TEMPLATE, MetadataChildKind.fromString("ШаблонURL")); //$NON-NLS-1$
        assertEquals(MetadataChildKind.METHOD, MetadataChildKind.fromString("Method")); //$NON-NLS-1$
        assertEquals(MetadataChildKind.METHOD, MetadataChildKind.fromString("httpMethod")); //$NON-NLS-1$
        assertEquals(MetadataChildKind.METHOD, MetadataChildKind.fromString("Метод")); //$NON-NLS-1$
        assertEquals("URLTemplate", MetadataChildKind.URL_TEMPLATE.getDisplayName()); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("Method", MetadataChildKind.METHOD.getDisplayName()); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void theChildrenOpsRefusalNowAdvertisesTheRouteKinds() {
        // children_ops op=add is meant to stay refused — but its message is the only place a caller
        // that reached for it learns what add_metadata_child can create.
        String message = ChildrenOpsValidator.createChildIntentRejectionMessage("add"); //$NON-NLS-1$
        assertTrue(message, message.contains("URLTemplate")); //$NON-NLS-1$
        assertTrue(message, message.contains("Method")); //$NON-NLS-1$
        assertTrue(message, message.contains("add_metadata_child")); //$NON-NLS-1$
    }

    // --- helpers --------------------------------------------------------------------------------

    private HTTPService httpService(String name) {
        HTTPService created = FACTORY.createHTTPService();
        created.setName(name);
        return created;
    }

    private Map<String, Object> childEntry(String name, String template) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name); //$NON-NLS-1$
        entry.put("template", template); //$NON-NLS-1$
        return entry;
    }

    private String create(
            MdObject parent,
            MetadataChildKind kind,
            String parentFqn,
            String name,
            Map<String, Object> properties
    ) {
        AddMetadataChildRequest request = new AddMetadataChildRequest(
                "AM", parentFqn, kind, name, null, null, properties); //$NON-NLS-1$
        return service.createGenericChildForResolvedParent(
                null, parent, request, null, Map.of(), kind);
    }
}
