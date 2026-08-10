package com.codepilot1c.core.tools.metadata;

import java.util.function.Predicate;

import com.codepilot1c.core.edt.validation.ValidationOperation;

/**
 * Decides whether {@code edt_validate_request} may issue a token for an
 * operation the calling endpoint cannot actually execute.
 *
 * <p>A multi-endpoint EDT exposes a different tool set per port (see
 * {@code discover_tools}, which answers strictly by the calling port's
 * profile). {@code edt_validate_request} knew nothing of that: it validated
 * {@code ensure_module_artifact} to {@code valid:true} and handed out a token
 * on a port whose profile gates the executing tool off — a green light for an
 * operation the caller cannot spend it on, and a wasted round-trip every time.
 * Reported 2026-08-08 in
 * {@code 2026-08-08-ensure-module-artifact-validates-but-gated-unreachable.md}.</p>
 *
 * <p>Fail-open by construction. The refusal fires only when the router
 * supplied the calling endpoint's own visibility test — with no predicate the
 * profile is genuinely unknown (an in-process agent call, a test), and
 * guessing from the global environment view could refuse a call that would
 * have succeeded.</p>
 */
final class EndpointOperationGate {

    private EndpointOperationGate() { }

    /**
     * @param operation the resolved operation, or {@code null} when it could
     *        not be resolved (then nothing is refused here)
     * @param requestedOperation the operation name as the caller spelled it,
     *        for the message
     * @param endpointVisible the calling endpoint's {@code toolName -> exposed?}
     *        test, or {@code null} when the profile is unknown
     * @return the refusal message, or {@code null} when a token may be issued
     */
    static String refusalMessageOrNull(
            ValidationOperation operation,
            String requestedOperation,
            Predicate<String> endpointVisible
    ) {
        if (operation == null || endpointVisible == null) {
            return null;
        }
        String toolName = ValidationPayloadKeyContract.targetToolName(operation);
        if (toolName == null || endpointVisible.test(toolName)) {
            return null;
        }
        String displayed = requestedOperation == null || requestedOperation.isBlank()
                ? toolName : requestedOperation;
        StringBuilder sb = new StringBuilder();
        sb.append('\'').append(displayed).append("' is not executable on this endpoint: "); //$NON-NLS-1$
        if (!toolName.equals(displayed)) {
            sb.append("it is dispatched by '").append(toolName).append("', which is "); //$NON-NLS-1$ //$NON-NLS-2$
        } else {
            sb.append("the tool is "); //$NON-NLS-1$
        }
        sb.append("not enabled in this endpoint's profile, so tools/call would reject it. ") //$NON-NLS-1$
                .append("No validation_token was issued — a token for a gated operation could not be spent. ") //$NON-NLS-1$
                .append("Use discover_tools to see what this port exposes, or validate on the endpoint " //$NON-NLS-1$
                        + "whose profile includes '").append(toolName).append("'."); //$NON-NLS-1$ //$NON-NLS-2$
        return sb.toString();
    }
}
