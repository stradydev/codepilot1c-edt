package com.codepilot1c.core.tools.metadata;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.Test;

import com.codepilot1c.core.edt.metadata.AddMetadataChildRequest;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
import com.codepilot1c.core.edt.metadata.MetadataChildKind;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;
import com.codepilot1c.core.edt.metadata.MetadataOperationResult;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
import com.codepilot1c.core.edt.validation.ValidationOperation;
import com.codepilot1c.core.tools.ToolResult;

/**
 * Tool-level plumbing for HTTP-service route authoring (feedback note
 * {@code 2026-09-11-add-metadata-child-cannot-create-httpservice-urltemplate}).
 *
 * <p>The refusal the note recorded was raised at the schema/enum edge, before any BM work: the
 * {@code child_kind} enum did not list {@code URLTemplate}, so {@code edt_validate_request} itself
 * answered {@code INVALID_METADATA_KIND}. These tests therefore pin the agent-facing contract (the
 * kinds are offered) and the payload path (the route properties survive normalization and the
 * validation token and reach the service request). What lands in the EMF model is pinned
 * behaviourally by {@code HttpServiceRouteChildTest}.</p>
 */
public class AddMetadataChildToolHttpServiceTest {

    @Test
    public void schemaOffersTheRouteKindsAndTheirProperties() {
        String schema = new AddMetadataChildTool().getParameterSchema();
        assertNotNull(schema);
        assertTrue("child_kind must offer URLTemplate:\n" + schema, //$NON-NLS-1$
                schema.contains("\"URLTemplate\"")); //$NON-NLS-1$
        assertTrue("child_kind must offer Method:\n" + schema, //$NON-NLS-1$
                schema.contains("\"Method\"")); //$NON-NLS-1$
        assertTrue("the template property must be documented:\n" + schema, //$NON-NLS-1$
                schema.contains("template")); //$NON-NLS-1$
        assertTrue("the handler property must be documented:\n" + schema, //$NON-NLS-1$
                schema.contains("handler")); //$NON-NLS-1$
        assertTrue("the nested owner form is the non-obvious part — it must be spelled out:\n" + schema, //$NON-NLS-1$
                schema.contains("HTTPService.Foo.URLTemplate.Bar")); //$NON-NLS-1$
    }

    @Test
    public void urlTemplatePropertiesReachTheMetadataServiceRequest() {
        StubMetadataService metadataService = new StubMetadataService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, new StubValidationService());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "HTTPService.MCPApi"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "URLTemplate"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "V1Whoami"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("properties", Map.of("template", "/v1/whoami")); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue("URLTemplate must no longer be refused at the enum:\n" + result.getContent(), //$NON-NLS-1$
                result.isSuccess());
        assertEquals(MetadataChildKind.URL_TEMPLATE, metadataService.lastRequest.childKind());
        assertEquals("/v1/whoami", metadataService.lastRequest.properties().get("template")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    @Test
    public void methodIsAddressedThroughTheTemplatesOwnFqn() {
        StubMetadataService metadataService = new StubMetadataService();
        AddMetadataChildTool tool = new AddMetadataChildTool(metadataService, new StubValidationService());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("httpMethod", "POST"); //$NON-NLS-1$ //$NON-NLS-2$
        properties.put("handler", "V1ToolCallPost"); //$NON-NLS-1$ //$NON-NLS-2$
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("project", "AM"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("parent_fqn", "HTTPService.MCPApi.URLTemplate.V1ToolCall"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("child_kind", "Method"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("name", "Post"); //$NON-NLS-1$ //$NON-NLS-2$
        params.put("properties", properties); //$NON-NLS-1$
        params.put("validation_token", "token-1"); //$NON-NLS-1$ //$NON-NLS-2$

        ToolResult result = tool.execute(params).join();

        assertTrue(result.getContent(), result.isSuccess());
        assertEquals(MetadataChildKind.METHOD, metadataService.lastRequest.childKind());
        assertEquals("a nested parent_fqn must pass through untouched", //$NON-NLS-1$
                "HTTPService.MCPApi.URLTemplate.V1ToolCall", metadataService.lastRequest.parentFqn()); //$NON-NLS-1$
        assertEquals("POST", metadataService.lastRequest.properties().get("httpMethod")); //$NON-NLS-1$ //$NON-NLS-2$
        assertEquals("V1ToolCallPost", metadataService.lastRequest.properties().get("handler")); //$NON-NLS-1$ //$NON-NLS-2$
    }

    private static final class StubMetadataService extends EdtMetadataService {
        private AddMetadataChildRequest lastRequest;

        @Override
        public MetadataOperationResult addMetadataChild(AddMetadataChildRequest request) {
            lastRequest = request;
            String fqn = request.parentFqn() + "." + request.childKind().getDisplayName() + "." + request.name(); //$NON-NLS-1$ //$NON-NLS-2$
            return new MetadataOperationResult(
                    true,
                    request.projectName(),
                    request.childKind().name(),
                    request.name(),
                    fqn,
                    "Metadata child object created successfully"); //$NON-NLS-1$
        }
    }

    private static final class StubValidationService extends MetadataRequestValidationService {
        private Map<String, Object> normalizedPayload;

        @Override
        public Map<String, Object> consumeToken(String token, ValidationOperation operation, String projectName) {
            if (!"token-1".equals(token)) { //$NON-NLS-1$
                throw new MetadataOperationException(
                        MetadataOperationCode.KNOWLEDGE_REQUIRED,
                        "unexpected token", false); //$NON-NLS-1$
            }
            return normalizedPayload;
        }

        @Override
        public Map<String, Object> normalizeAddChildPayload(
                String project,
                String parentFqn,
                String childKindValue,
                String name,
                String synonym,
                String comment,
                Map<String, Object> properties) {
            Map<String, Object> payload = super.normalizeAddChildPayload(
                    project, parentFqn, childKindValue, name, synonym, comment, properties);
            normalizedPayload = payload;
            return payload;
        }
    }
}
