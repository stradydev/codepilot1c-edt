# `bsl_module_context.moduleType` is constant `COMMON_MODULE` for every module kind

**Found:** 2026-08-10, incidentally while reproducing the `bsl_object_context` doubled-`src/` bug
(CHANGELOG Round-19). **Build:** `0.1.7.20260810-0712`, `workspace-sandbox`, project
`Accounting management`.

**FIXED** the same day — CHANGELOG Round-20, `BslModuleTypeResolver` + `BslModuleTypeResolverTest`
(13/13). Live validation still pending a sandbox redeploy. The plan below was followed, with two
findings that changed it; both are recorded at the end.

## Observed

Three different module kinds of the same object, three identical answers:

| `filePath` | `moduleType` | `totalMethods` |
|---|---|---|
| `Documents/CasinoCashflowTransactions/ObjectModule.bsl` | `COMMON_MODULE` | 8 |
| `Documents/CasinoCashflowTransactions/ManagerModule.bsl` | `COMMON_MODULE` | 6 |
| `Documents/CasinoCashflowTransactions/Forms/DocumentForm/Module.bsl` | `COMMON_MODULE` | 2 |

The method counts differ, so the right file is being read each time — only the kind is wrong. A form
module reported as a common module is a misleading answer to a question a caller asks precisely to decide
what is legal in that module (`&AtClient` availability, event handlers, export rules).

## Where it comes from

`BslSemanticService.normalizeModuleType` (line ~1007) just returns
`context.module().getModuleType().getLiteral()` — no derivation of its own. So EDT's own
`Module.getModuleType()` is answering `COMMON_MODULE` for all three. The likely reason is that the module
resource is loaded standalone, without its metadata owner context, and EDT falls back to its default; that
was **not** confirmed.

## Why it was not fixed on the spot

The obvious fix — derive the kind from the path, which is exactly what the EDT project layout encodes
(`ObjectModule.bsl`, `ManagerModule.bsl`, `Forms/<X>/Module.bsl`, `CommonModules/<X>/Module.bsl`,
`RecordSetModule.bsl`, `ValueManagerModule.bsl`, `CommandModule.bsl`, …) — requires emitting the SAME
strings EDT would, and those literals cannot be guessed: the observed value is upper-snake
(`COMMON_MODULE`), so e.g. record-set could be `RECORDSET_MODULE` or `RECORD_SET_MODULE`. Inventing a
spelling would replace a wrong answer with an inconsistent one.

## How to fix it without guessing

1. Read the literals from `com._1c.g5.v8.dt.bsl.model.ModuleType` in
   `com._1c.g5.v8.dt.bsl.model_12.0.0.v202602241426.jar` (EDT install `plugins/`), or enumerate
   `ModuleType.VALUES` once in a scratch probe.
2. Put the **path → enum name** mapping in core as pure logic (testable; this bundle's rule, not the UI's).
3. In `normalizeModuleType`, resolve the derived name against the real `ModuleType` constants and use
   that constant's literal. If the derived name matches no constant, keep EDT's value — so a wrong entry
   degrades to today's behaviour instead of emitting a made-up string.

Prefer the path-derived kind over EDT's when both resolve: the path is the authoritative encoding of the
kind in an EDT project layout, and where EDT does resolve correctly the two agree anyway.

## How it was actually fixed (2026-08-10)

Two findings changed the plan above, both for the better.

**1. The hypothesis in "Where it comes from" was wrong in its diagnosis but right in its conclusion.**
It is not that EDT "falls back to its default" — `ModuleImpl.getModuleType()` is a bare getter over a
stored field (verified in the bytecode), `setModuleType` is called only from
`BslDerivedStateComputer`/`BslUtil`/`BslBinaryResourceFactory`, and none of those touch a module this
bundle takes straight from the parser. The field is simply never assigned, so its EMF default
(`COMMON_MODULE`, the first literal) is what was being reported. Nothing was mis-deriving anything.

**2. No path table was needed — EDT exposes its own mapping.** `BslUtil.computeModuleType(Module,
IQualifiedNameFilePathConverter)` is public: it takes `EcoreUtil.getURI(module).toPlatformString(false)`,
turns it into an FQN via the converter, and maps the FQN to a `ModuleType`. Delegating to it removes the
whole reason the fix was parked (guessing literals) *and* removes the maintenance risk of a local copy.
The converter comes from the BSL language injector behind the resource
(`XtextResource.getResourceServiceProvider().get(...)`), where EDT's own derived-state computer gets it;
EDT binds the interface `toService()`, so a non-blocking OSGi service lookup is the second source.

Recorded for anyone who needs the mapping without a decompiler — EDT's `getModuleTypeByQname`, keyed on
the module's FQN (literal spellings are the enum constant names; there are 16, identical in the
2025.2.3 and 2025.2.5 jars):

| last FQN segment | condition | kind |
|---|---|---|
| `Module` | segment `n-2` is `Form` or `BaseForm` | `FORM_MODULE` |
| `Module` | first segment `CommonModule` | `COMMON_MODULE` |
| `Module` | first segment `WebService` / `HTTPService` / `IntegrationService` / `Bot` / `WebSocketClient` | the matching `*_MODULE` |
| `ObjectModule` | — | `OBJECT_MODULE` |
| `ManagerModule` | — | `MANAGER_MODULE` |
| `RecordSetModule` | — | `RECORDSET_MODULE` (**not** `RECORD_SET_MODULE`) |
| `ValueManagerModule` | — | `VALUE_MANAGER_MODULE` |
| `CommandModule` | — | `COMMAND_MODULE` |
| `ManagedApplicationModule` | — | `MANAGED_APP_MODULE` |
| `OrdinaryApplicationModule` | — | `ORDINARY_APP_MODULE` |
| `ExternalConnectionModule` | — | `EXTERNAL_CONN_MODULE` |
| `SessionModule` | — | `SESSION_MODULE` |
| anything else, or fewer than 2 segments | — | `null` |

A `null` from that mapping — and a URI that is not a platform one, where `computeModuleType` short-circuits
to `COMMON_MODULE` without consulting anything — is treated as "kind unknown", not as "common module":
the stored default is reported only for a path that could actually carry it (`CommonModules/…`), or when
the caller supplied no path to contradict it.
