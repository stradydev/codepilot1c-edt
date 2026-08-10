# `bsl_module_context.moduleType` is constant `COMMON_MODULE` for every module kind

**Found:** 2026-08-10, incidentally while reproducing the `bsl_object_context` doubled-`src/` bug
(CHANGELOG Round-19). **Build:** `0.1.7.20260810-0712`, `workspace-sandbox`, project
`Accounting management`. **Not fixed** — scoped and grounded here so it needs no re-investigation.

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
