/*
 * Copyright (c) 2024 Example
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, version 3.
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.codepilot1c.core.tools.workspace;

import java.util.concurrent.CompletableFuture;

import com.codepilot1c.core.edt.runtime.EdtInfobaseAssociationService;
import com.codepilot1c.core.edt.runtime.EdtToolErrorCode;
import com.codepilot1c.core.edt.runtime.EdtToolException;
import com.codepilot1c.core.logging.LogSanitizer;
import com.codepilot1c.core.logging.VibeLogger;
import com.codepilot1c.core.tools.AbstractTool;
import com.codepilot1c.core.tools.ToolMeta;
import com.codepilot1c.core.tools.ToolParameters;
import com.codepilot1c.core.tools.ToolResult;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Per-branch infobase association surgery without a checkout: list all branch contexts with
 * their bindings, bind an existing registry infobase under ANY branch, copy a branch's bindings
 * to another branch, or dissociate. Complements {@code connect_infobase} (which owns registry
 * lifecycle and binds into the CURRENT context only).
 */
@ToolMeta(
        name = "manage_associations",
        category = "workspace",
        mutating = true,
        tags = {"workspace", "edt"})
public class ManageAssociationsTool extends AbstractTool {

    private static final VibeLogger.CategoryLogger LOG = VibeLogger.forClass(ManageAssociationsTool.class);

    private static final String SCHEMA = """
            {
              "type": "object",
              "properties": {
                "action": {
                  "type": "string",
                  "enum": ["list", "bind", "copy", "dissociate"],
                  "description": "list: all branch contexts with bound infobases and default markers. bind: attach an EXISTING registry infobase to a branch's context (no checkout needed). copy: replicate one branch's bindings onto another branch. dissociate: detach infobase(s) from a branch's context ONLY — the EDT registry entry survives (verified and repaired on every call), so the exact inverse bind reattaches it; no re-registration and no credentials needed."
                },
                "project_name": {
                  "type": "string",
                  "description": "EDT project whose associations are inspected/changed"
                },
                "branch": {
                  "type": "string",
                  "description": "bind/dissociate: target git branch (short name, e.g. 'task-C'; a full refs/heads/... ref is also accepted)."
                },
                "from_branch": {
                  "type": "string",
                  "description": "copy: source branch whose bindings are replicated."
                },
                "to_branch": {
                  "type": "string",
                  "description": "copy: destination branch."
                },
                "infobase_name": {
                  "type": "string",
                  "description": "bind: display name of the EXISTING registry entry to attach (register new infobases via connect_infobase). dissociate: limit to this name (omit to detach all)."
                },
                "database_path": {
                  "type": "string",
                  "description": "bind: file-infobase folder path as an alternative/additional selector for the registry entry (matched canonically; combine with infobase_name to disambiguate)."
                },
                "set_default": {
                  "type": "boolean",
                  "description": "bind/copy: make the bound infobase the branch's default (default: true)."
                }
              },
              "required": ["action", "project_name"]
            }
            """; //$NON-NLS-1$

    private final EdtInfobaseAssociationService service;

    public ManageAssociationsTool() {
        this(new EdtInfobaseAssociationService());
    }

    public ManageAssociationsTool(EdtInfobaseAssociationService service) {
        this.service = service;
    }

    @Override
    public String getDescription() {
        return "Inspects and edits per-branch infobase associations of an EDT project WITHOUT " //$NON-NLS-1$
                + "checking branches out (list/bind/copy/dissociate). Attaches only infobases " //$NON-NLS-1$
                + "already registered in EDT — use connect_infobase to register a new one."; //$NON-NLS-1$
    }

    @Override
    public String getParameterSchema() {
        return SCHEMA;
    }

    @Override
    public boolean requiresConfirmation() {
        // Reversible binding edits; no credentials, no data. The only registry write is the
        // dissociate guard's restore of a row EDT dropped — a repair, never a removal.
        return false;
    }

    @Override
    public boolean isDestructive() {
        return false;
    }

    @Override
    protected CompletableFuture<ToolResult> doExecute(ToolParameters params) {
        return CompletableFuture.supplyAsync(() -> {
            String opId = LogSanitizer.newId("assoc"); //$NON-NLS-1$
            java.util.Map<String, Object> raw = params.getRaw() == null ? java.util.Map.of() : params.getRaw();
            String action = asString(raw.get("action")); //$NON-NLS-1$
            String projectName = asString(raw.get("project_name")); //$NON-NLS-1$
            String branch = asString(raw.get("branch")); //$NON-NLS-1$
            String fromBranch = asString(raw.get("from_branch")); //$NON-NLS-1$
            String toBranch = asString(raw.get("to_branch")); //$NON-NLS-1$
            String infobaseName = asString(raw.get("infobase_name")); //$NON-NLS-1$
            String databasePath = asString(raw.get("database_path")); //$NON-NLS-1$
            boolean setDefault = !Boolean.FALSE.equals(raw.get("set_default")) //$NON-NLS-1$
                    && !"false".equalsIgnoreCase(asString(raw.get("set_default"))); //$NON-NLS-1$ //$NON-NLS-2$

            LOG.info("[%s] manage_associations action=%s project=%s", opId, action, projectName); //$NON-NLS-1$
            try {
                if (action == null) {
                    throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                            "action is required: list | bind | copy | dissociate"); //$NON-NLS-1$
                }
                JsonObject payload = switch (action) {
                    case "list" -> list(opId, projectName); //$NON-NLS-1$
                    case "bind" -> bind(opId, projectName, branch, infobaseName, databasePath, setDefault); //$NON-NLS-1$
                    case "copy" -> copy(opId, projectName, fromBranch, toBranch, setDefault); //$NON-NLS-1$
                    case "dissociate" -> dissociate(opId, projectName, branch, infobaseName); //$NON-NLS-1$
                    default -> throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                            "unknown action '" + action + "': expected list | bind | copy | dissociate"); //$NON-NLS-1$ //$NON-NLS-2$
                };
                // A handler may downgrade its own payload (dissociate does when the registry-survival
                // guard could not restore a dropped row): honor that instead of forcing success.
                return isSuccess(payload)
                        ? ToolResult.success(pretty(payload), ToolResult.ToolResultType.CODE)
                        : ToolResult.failure(pretty(payload));
            } catch (EdtToolException e) {
                LOG.warn(String.format("[%s] manage_associations action=%s failed with %s: %s", //$NON-NLS-1$
                        opId, action, e.getCode() == null ? "<unknown>" : e.getCode().name(), //$NON-NLS-1$
                        e.getMessage() == null ? "" : e.getMessage()), e); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayload(opId, projectName, e)));
            } catch (Exception e) {
                LOG.error(String.format("[%s] manage_associations action=%s failed", opId, action), e); //$NON-NLS-1$
                return ToolResult.failure(pretty(errorPayload(opId, projectName,
                        new EdtToolException(EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE,
                                e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), e))));
            }
        });
    }

    private JsonObject list(String opId, String projectName) {
        JsonObject payload = base(opId, projectName);
        JsonArray contexts = new JsonArray();
        for (EdtInfobaseAssociationService.ContextView view : service.listContexts(projectName)) {
            JsonObject ctx = new JsonObject();
            ctx.addProperty("context", view.context()); //$NON-NLS-1$
            if (view.branch() != null) {
                ctx.addProperty("branch", view.branch()); //$NON-NLS-1$
            }
            if (view.current()) {
                ctx.addProperty("current", true); //$NON-NLS-1$
            }
            JsonArray infobases = new JsonArray();
            for (EdtInfobaseAssociationService.InfobaseView ib : view.infobases()) {
                JsonObject entry = new JsonObject();
                entry.addProperty("name", ib.name()); //$NON-NLS-1$
                if (ib.uuid() != null) {
                    entry.addProperty("uuid", ib.uuid()); //$NON-NLS-1$
                }
                if (ib.connection() != null) {
                    entry.addProperty("connection", ib.connection()); //$NON-NLS-1$
                }
                if (ib.isDefault()) {
                    entry.addProperty("default", true); //$NON-NLS-1$
                }
                infobases.add(entry);
            }
            ctx.add("infobases", infobases); //$NON-NLS-1$
            contexts.add(ctx);
        }
        payload.add("contexts", contexts); //$NON-NLS-1$
        return payload;
    }

    private JsonObject bind(String opId, String projectName, String branch, String infobaseName,
            String databasePath, boolean setDefault) {
        EdtInfobaseAssociationService.BindOutcome outcome =
                service.bind(projectName, branch, infobaseName, databasePath, setDefault);
        JsonObject payload = base(opId, projectName);
        payload.addProperty("context", outcome.context()); //$NON-NLS-1$
        payload.addProperty("infobase", outcome.infobaseName()); //$NON-NLS-1$
        if (outcome.uuid() != null) {
            payload.addProperty("uuid", outcome.uuid()); //$NON-NLS-1$
        }
        payload.addProperty("default", Boolean.valueOf(outcome.setDefault())); //$NON-NLS-1$
        return payload;
    }

    private JsonObject copy(String opId, String projectName, String fromBranch, String toBranch,
            boolean setDefault) {
        if (fromBranch == null || toBranch == null) {
            throw new EdtToolException(EdtToolErrorCode.INVALID_ARGUMENT,
                    "from_branch and to_branch are required for action=copy"); //$NON-NLS-1$
        }
        EdtInfobaseAssociationService.CopyOutcome outcome =
                service.copy(projectName, fromBranch, toBranch, setDefault);
        JsonObject payload = base(opId, projectName);
        payload.addProperty("from_context", outcome.fromContext()); //$NON-NLS-1$
        payload.addProperty("to_context", outcome.toContext()); //$NON-NLS-1$
        JsonArray copied = new JsonArray();
        outcome.copied().forEach(copied::add);
        payload.add("copied", copied); //$NON-NLS-1$
        if (outcome.defaultName() != null) {
            payload.addProperty("default", outcome.defaultName()); //$NON-NLS-1$
        }
        return payload;
    }

    private JsonObject dissociate(String opId, String projectName, String branch, String infobaseName) {
        EdtInfobaseAssociationService.DissociateOutcome outcome =
                service.dissociate(projectName, branch, infobaseName);
        JsonObject payload = base(opId, projectName);
        payload.addProperty("context", outcome.context()); //$NON-NLS-1$
        JsonArray removed = new JsonArray();
        outcome.removed().forEach(removed::add);
        payload.add("removed", removed); //$NON-NLS-1$
        if (!outcome.registryIntact()) {
            // The registry-survival guard fired. Surface it either way: a silent "removed: [...]"
            // would let the caller believe the entry is still bindable when it may not be.
            JsonArray restored = new JsonArray();
            outcome.registryRestored().forEach(restored::add);
            payload.add("registry_restored", restored); //$NON-NLS-1$
        }
        if (!outcome.registryLost().isEmpty()) {
            JsonArray lost = new JsonArray();
            outcome.registryLost().forEach(lost::add);
            payload.add("registry_lost", lost); //$NON-NLS-1$
            payload.addProperty("success", Boolean.FALSE); //$NON-NLS-1$
            payload.addProperty("error_code", //$NON-NLS-1$
                    EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE.name());
            payload.addProperty("message", //$NON-NLS-1$
                    "Dissociated from the branch context, but EDT dropped the flat registry entry " //$NON-NLS-1$
                            + "of " + outcome.registryLost() //$NON-NLS-1$
                            + " and it could not be re-registered — the infobase registry is now " //$NON-NLS-1$
                            + "INCOMPLETE and bind of that name will fail until it is restored."); //$NON-NLS-1$
            payload.addProperty("hint", //$NON-NLS-1$
                    "re-register with connect_infobase (kind=file needs only database_path; " //$NON-NLS-1$
                            + "kind=server needs the infobase's server credentials, so its owner " //$NON-NLS-1$
                            + "must do it), then bind again"); //$NON-NLS-1$
        }
        return payload;
    }

    /** Reads a handler payload's own {@code success} flag (absent/malformed counts as success). */
    private static boolean isSuccess(JsonObject payload) {
        com.google.gson.JsonElement flag = payload == null ? null : payload.get("success"); //$NON-NLS-1$
        if (flag == null || !flag.isJsonPrimitive()) {
            return true;
        }
        return flag.getAsJsonPrimitive().isBoolean() ? flag.getAsBoolean() : true;
    }

    private static JsonObject base(String opId, String projectName) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("success", true); //$NON-NLS-1$
        payload.addProperty("project", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        return payload;
    }

    private static JsonObject errorPayload(String opId, String projectName, EdtToolException e) {
        JsonObject payload = new JsonObject();
        payload.addProperty("op_id", opId); //$NON-NLS-1$
        payload.addProperty("success", false); //$NON-NLS-1$
        payload.addProperty("project", projectName == null ? "" : projectName); //$NON-NLS-1$ //$NON-NLS-2$
        EdtToolErrorCode code = e.getCode() == null ? EdtToolErrorCode.EDT_SERVICE_UNAVAILABLE : e.getCode();
        payload.addProperty("error_code", code.name()); //$NON-NLS-1$
        payload.addProperty("message", e.getMessage() == null ? "" : e.getMessage()); //$NON-NLS-1$ //$NON-NLS-2$
        if (code == EdtToolErrorCode.INFOBASE_NOT_FOUND) {
            // Do NOT reflexively point at connect_infobase: for a kind=server entry that advice is
            // unactionable (and unsafe) for a caller with no server credentials. Feedback 2026-09-14.
            payload.addProperty("hint", //$NON-NLS-1$
                    "manage_associations attaches only EXISTING registry entries. Check the name " //$NON-NLS-1$
                            + "first (action=list, or connect_infobase's registry view) — " //$NON-NLS-1$
                            + "dissociate never removes a registry entry, so the name may simply " //$NON-NLS-1$
                            + "be spelled differently. Only if the entry is genuinely gone, " //$NON-NLS-1$
                            + "re-register with connect_infobase: kind=file needs just " //$NON-NLS-1$
                            + "database_path, but kind=server (Srvr/Ref) needs the infobase's " //$NON-NLS-1$
                            + "server credentials — without them the infobase owner must " //$NON-NLS-1$
                            + "re-register it"); //$NON-NLS-1$
        } else if (code == EdtToolErrorCode.EDT_LEASE_HELD) {
            payload.addProperty("hint", //$NON-NLS-1$
                    "the target branch is leased by another stack; release it there " //$NON-NLS-1$
                            + "(manage_leases action=release) or steal a stale lease " //$NON-NLS-1$
                            + "(manage_leases action=take force=true)"); //$NON-NLS-1$
        }
        return payload;
    }

    private static String pretty(JsonObject object) {
        return new GsonBuilder().setPrettyPrinting().create().toJson(object);
    }

    private static String asString(Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
