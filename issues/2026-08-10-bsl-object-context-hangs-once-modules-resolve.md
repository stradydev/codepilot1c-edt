# `bsl_object_context` times out whenever it actually reaches a module — Round-19 traded a wrong answer for no answer

**Found:** 2026-08-10, live on sandbox build `0.1.7.20260810-1011` (the build carrying Round-16…26).
**Status:** OPEN, reproduced, root cause narrowed to `BslObjectContextService#collectModuleSections`.
**Severity:** high — the tool is unusable for its primary purpose. It is a *regression in reach*, not in
correctness: nothing returns a wrong answer, but the call that used to answer "missing" now returns
nothing at all.

## What happens

Every `bsl_object_context` call that actually enters the module loop times out. Every call that skips
it returns instantly.

| Call | Result |
|---|---|
| `Document.AdvanceReport`, `methods=signatures` | **timeout** |
| `Document.AdvanceReport`, `methods=signatures`, `manager_module_methods=false` | **timeout** |
| `Document.AdvanceReport`, `methods=signatures`, both module flags `false` | instant, `modules: []` |
| `Document.CasinoCashflowTransactions`, `methods=none` | instant, `modules: []` |
| `CommonModule.OK` in `TestConfiguration` (2-file project, tiny module) | **timeout** |

The two "instant" rows are the ones that never run `collectModuleSections`: `methods=none` skips the
section wholesale (`BslObjectContextService:113`), and clearing both module flags makes
`includesModulePath` reject every path.

## Why it is not what it looks like

Three plausible explanations were tested and **refuted** — worth recording so they are not re-tried:

1. **Not the underlying services.** Both calls the loop makes are fast standalone on the very same
   file: `bsl_module_context` on `Documents/AdvanceReport/ObjectModule.bsl` returns in well under a
   second (`moduleType: OBJECT_MODULE`, 4 methods), and `bsl_module_exports` with the same `limit=200`
   the aggregator uses returns 2 items just as fast.
2. **Not the path spelling.** Round-19's `SourceFilePathCandidates` was the obvious suspect, since an
   already-prefixed input is tried as `src/src/…` first. But *both* spellings resolve instantly through
   `bsl_module_context` — the documented `Documents/…` and the `src/Documents/…` a caller pastes out of
   `glob`. (That fallback is therefore **confirmed working live**, which is the other half of Round-19.)
3. **Not cold Xtext loading.** The aggregator still timed out after the same resources had just been
   loaded and answered by the two standalone calls.
4. **Not GSON choking on a cyclic graph.** `BslModuleContextResult` and `BslModuleExportsResult` are
   both flat POJOs (String/int/`List<BslMethodInfo>`), so `GSON.toJsonTree` on them is trivial.

**Not size-related either** — it reproduces on a 2-file project's small `CommonModule` just as it does
on a 291-line document module.

## Where that leaves it

The hang is inside `collectModuleSections` itself, on the **success** path — the one that Round-19
(`0edd4e0`) made reachable for the first time. Before that fix every module lookup threw
`EdtAstException` and was recorded as `status: missing`, so the body after
`bslSemanticService.getModuleContext(...)` had effectively never executed against a resolvable file.
Fixing the path exposed whatever is wrong further in.

That also means the original feedback note
(`2026-08-08-bsl-object-context-false-missing-modules-existing-files`) is **not yet closeable**: the
"missing" is gone, but the caller still cannot get the modules.

## Suggested next step

This is a live-only symptom with no exception surfacing, so it fits the diagnostic-build-round method:
ship a build that logs entry/exit around each of the four steps in the loop body
(`getModuleContext`, `toJsonTree(ctx)`, `getModuleExports`, `toJsonTree(exports)`) with elapsed
millis, and let one live call say which one never returns. A thread dump taken while a call is stuck
would settle it in one shot if the workbench can be reached at that moment.

Do **not** guess-fix by adding a timeout around the loop: that would convert a hang into a partial
answer and hide the real defect.

## Also observed in the same session

`Document.CasinoCashflowTransactions` — the object named in the original feedback note — has **no**
`ObjectModule.bsl` and no `ManagerModule.bsl` on disk at all; only two form modules and two command
modules. Any future check on that object must not read an empty `modules` as proof of a defect.
