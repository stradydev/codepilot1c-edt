# `bsl_module_context` never resolves a module's `owner` — the BM-aware resource set is unreachable

**Found:** 2026-08-10, while root-causing
`2026-08-10-bsl-object-context-hangs-once-modules-resolve.md`.
**Status:** OPEN — diagnosed, not fixed. Not a regression; it has presumably always been this way.
**Severity:** medium. The tool answers, and the answer it gives is correct as far as it goes; the
owner link — the first field its own description promises — is silently always absent.

## Symptom

Live on the sandbox, `bsl_module_context` on `CommonModules/OK/Module.bsl`:

```json
{"projectName":"TestConfiguration","filePath":"CommonModules/OK/Module.bsl",
 "moduleType":"COMMON_MODULE","defaultPragmas":[],"totalMethods":0,…}
```

No `ownerClass` / `ownerName` / `ownerUri`. Gson drops nulls, so their absence means
`context.module().getOwner()` returned `null`. The tool's description reads "Returns a BSL module's
context: **owner**, module kind, pragmas, and method count" — the owner never arrives.

## Why

`BslSemanticService.resolveResourceSet` wants EDT's `BmAwareResourceSetProvider`, which links a
parsed module to its BM (metadata) object. That provider is unreachable through the mechanism the
plugin uses, so every call lands in `createStandaloneResourceSet()` — a plain Xtext resource set that
parses the BSL fine but knows nothing about the configuration, hence no owner.

The provider is a **Guice binding, not an OSGi service** — see the sibling issue for the bytecode and
live proof. So `ServiceTracker` can never produce it, and the fallback is not a rare degraded path,
it is *the* path, 100 % of the time.

Beyond `owner`, anything that needs configuration scope rather than plain parsing is answering from
the weaker resource set — worth checking on `bsl_type_at_position`, `bsl_scope_members` and
`bsl_symbol_at_position` before assuming they are fine.

## What is already grounded (do not re-derive)

Decompile of `com._1c.g5.v8.dt.bm.xtext_17.0.600` (EDT 2025.2.3):

- `BmAwareResourceSetProvider` is a **public concrete class** in the exported package
  `com._1c.g5.v8.dt.bm.xtext`, implementing `org.eclipse.xtext.ui.resource.IResourceSetProvider`,
  with a public no-arg constructor and **four non-optional `@Inject` fields**:
  `IExternalContentSupport`, `IBmModelManager`, `IV8ProjectManager`, `IDtProjectManager`.
- `CoreModule.configure()` (public, `com._1c.g5.v8.dt.bm.xtext`) does
  `bind(IResourceSetProvider.class).to(BmAwareResourceSetProvider.class)` — **not** singleton-scoped —
  and installs `ExternalDependenciesModule`. `CoreModule` is language-agnostic: it is meant to be
  installed into a language injector.
- `BmXtextPlugin` exposes **public** `getPlugin()` + **public** `getInjector()`, but its injector is
  built from `ServiceModule` + `ExternalDependenciesModule` only — **no `CoreModule`**, and
  `ExternalDependenciesModule` binds 19 EDT services that do *not* include
  `IExternalContentSupport`. So `injector.getInstance(BmAwareResourceSetProvider.class)` on **this**
  injector JIT-binds the class and then fails injecting that field. **This route is a dead end** —
  tested by inspection, not worth re-trying.
- The BSL UI injector is *not* the source either: `BslUiModule.bindIResourceSetProvider()` returns
  Xtext's plain `SimpleResourceSetProvider`. Reachable as
  `BslActivator.getInstance().getInjector("com._1c.g5.v8.dt.bsl.Bsl")` (all public; language-id
  constant `BslActivator.COM__1C_G5_V8_DT_BSL_BSL`) — but it hands back the wrong provider.

## Open question that decides the fix

**Which injector installs `CoreModule`?** That injector's
`getInstance(org.eclipse.xtext.ui.resource.IResourceSetProvider.class)` is the provider, fully
injected, and the fix is then the same shape as `PublishDelegateRegistryResolver`: reflectively reach
that injector, ask for the binding, cache it, fall back to the standalone set on any failure.

Answer it by scanning the EDT plugin jars for references to
`com._1c.g5.v8.dt.bm.xtext.CoreModule` (constant-pool grep over the `plugins/` tree) — the MD/DT
language bundles and the builder are the likely holders.

Two cheaper alternatives if that scan comes up empty:

1. Instantiate `BmAwareResourceSetProvider` ourselves and satisfy the four fields from services we
   already track (`IBmModelManager`, `IV8ProjectManager`, `IDtProjectManager` are all OSGi-resolvable
   today — only `IExternalContentSupport` would need Xtext's default impl). Reflective field
   injection, no injector needed. Fragile against a field-set change, but each field is matched by
   type, so a rename does not break it.
2. Decide the owner link is not worth it and **fix the promise instead** — drop `owner` from the tool
   description and emit an explicit `"owner": null, "owner_unavailable_reason": …` so a caller is not
   left guessing whether the module genuinely has no owner.

Do not ship (1) or (2) before answering the question above; the injector route is the only one that
also repairs configuration scope for the other `bsl_*` tools.
