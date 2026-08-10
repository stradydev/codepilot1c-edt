# `bsl_object_context` times out whenever it actually reaches a module — Round-19 traded a wrong answer for no answer

**Found:** 2026-08-10, live on sandbox build `0.1.7.20260810-1011` (the build carrying Round-16…26).
**Status:** **ROOT-CAUSED and FIXED** 2026-08-10 (same day) — not a hang at all. See §Root cause.
**Severity:** was high — the tool was unusable for its primary purpose.

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

## Root cause — a 30 s OSGi wait that can never succeed, paid per delegate call

It never hung. It was **exactly 60 s** on a one-module object and **exactly 120 s** on a two-module
Document, and the MCP client timed out first.

`BslSemanticService.resolveResourceSet` → `EdtServiceGateway.getResourceSetProvider()` →
`VibeCorePlugin.getResourceSetProvider()` → `getTrackedService(...)`, which grants an unregistered
service `EDT_SERVICE_WAIT_TOTAL_MS` = **30 s** to appear before giving up. For
`BmAwareResourceSetProvider` that wait is unwinnable — the provider is **not an OSGi service at all**:

- **Bytecode** (`com._1c.g5.v8.dt.bm.xtext_17.0.600`, EDT 2025.2.3): the activator
  `BmXtextPlugin.lambda$1()` publishes exactly four services through
  `InjectorAwareServiceRegistrator` — `BslMarkerRemover` (managed), `BuildOrchestrator`,
  `IResourceDescriptionRepository`, `IDependentModelProvider`. The provider is not among them. It is
  bound in `com._1c.g5.v8.dt.bm.xtext.CoreModule` as Xtext's
  `org.eclipse.xtext.ui.resource.IResourceSetProvider` — a **Guice language-injector** binding.
  Exactly the mismatch already documented for `IWebServerPublishDelegateRegistry`
  (`PublishDelegateRegistryResolver`).
- **Live**: the sandbox workspace log held 21 `EDT service not available after wait (30000 ms)`
  entries — **21 of 21 for this service, none for any other tracked service**, over a six-hour
  session with both projects READY. It has never once resolved.

So each of the aggregator's delegate calls (`getModuleContext`, `getModuleExports`) burned a flat
30 s in `ServiceTracker.waitForService`, then fell back to a standalone Xtext resource set and
produced a correct answer. One module → 2 × 30 s. A Document → 4 × 30 s.

### How it was caught in one shot

A thread dump of the live sandbox EDT (`jstack <pid>`; the sandbox instance is identifiable by
`-data file:/…/workspace-sandbox/` in its command line) taken while a call was stuck named the frame
directly:

```
org.osgi.util.tracker.ServiceTracker.waitForService(ServiceTracker.java:507)
com.codepilot1c.core.internal.VibeCorePlugin.getTrackedService(VibeCorePlugin.java:483)
com.codepilot1c.core.internal.VibeCorePlugin.getResourceSetProvider(VibeCorePlugin.java:344)
com.codepilot1c.core.edt.ast.EdtServiceGateway.getResourceSetProvider(EdtServiceGateway.java:126)
com.codepilot1c.core.edt.lang.BslSemanticService.resolveResourceSet(BslSemanticService.java:492)
…
com.codepilot1c.core.edt.context.BslObjectContextService.collectModuleSections(…:171)
```

**No diagnostic build round was needed.** `jstack` attaches to the running EDT (Zulu 17 JDK is on
this box, EDT runs HotSpot 17), so a live-only symptom in a *foreground* call is one dump away from a
stack. Prefer this over shipping a logging build whenever the symptom can be held open on demand.

### Why the earlier four refutations all missed it

They were all aimed inside the loop body, but the cost sat in service resolution *inside each
delegate call* — the one place shared by the loop and the "fast" standalone control.

Refutation 1 ("not the underlying services — both are fast standalone, well under a second") was
simply **a mismeasurement**: timed properly, `bsl_module_context` on the very same file takes
**exactly 30 s**. The whole `bsl_*` family paid it, on every call, and nobody noticed because a
single call stayed under the client timeout. "Feels fast" is not a measurement — stamp the clock.

## Fix

`VibeCorePlugin.getResourceSetProvider()` no longer routes through the blocking `getTrackedService`;
it is now the non-blocking registry lookup (identical to `peekResourceSetProvider()`), with the
evidence in its Javadoc and a once-per-session log line explaining the absence. Every sibling
service keeps its 30 s grace — they legitimately appear during startup, and none of them ever timed
out.

All four consumers already degraded gracefully on `null` (standalone resource set / skipped line
number), so this changes latency only, never the answer:

| Consumer | Was |
|---|---|
| `BslSemanticService.resolveResourceSet` (whole `bsl_*` family) | 30 s per call |
| `BslObjectContextService.collectModuleSections` | 30 s × modules × 2 |
| `EdtReferenceService.extractLineNumberFromSourceUri` | 30 s **per resolved reference** |
| `EdtPlatformDocumentationService.resolveResourceSet` | 30 s per call |

No unit test accompanies the fix, deliberately: the property is "does not block 30 s inside a live
OSGi service registry", which no in-reactor test can observe (with a `null` tracker — the state in
the test runtime — the old code also returned instantly, so a test would have passed before the fix
too). Validation is a live latency measurement, recorded in the CHANGELOG.

## Follow-up, tracked separately

The standalone fallback answers, but it answers *worse* — the module's `owner` never resolves. See
`2026-08-10-bsl-module-owner-unresolved-standalone-resource-set.md`.

## Also observed in the same session

`Document.CasinoCashflowTransactions` — the object named in the original feedback note — has **no**
`ObjectModule.bsl` and no `ManagerModule.bsl` on disk at all; only two form modules and two command
modules. Any future check on that object must not read an empty `modules` as proof of a defect.
