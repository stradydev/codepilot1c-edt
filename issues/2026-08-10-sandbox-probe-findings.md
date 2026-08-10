# Two defects observed on the sandbox, 2026-08-10

Found incidentally while live-validating `create_metadata adopt_existing` (CHANGELOG Round-15).
Both were observed on build `0.1.7.20260810-0639`, `workspace-sandbox`, project `TestConfiguration`.

**Status after re-probing on build `0.1.7.20260810-0712`:** #1 does not exist — it was a reading
mistake on my side, corrected below. #2 is real, and the probe that this file said "was not verified"
has now been run: the defect is **broader** than described.

## 1. ~~`get_diagnostics` answers about the wrong project when the selector is misspelled~~ — NOT A DEFECT

**Retracted 2026-08-10.** Re-probed on build `0712` with `projectName='TestConfiguration'`: the answer
is indeed headed `## Diagnostics: /Accounting management`, but it carries the advisory note in full —

```
Note: get_diagnostics ignored an unknown parameter: 'projectName' (did you mean 'project_name'?).
It had no effect on this call. Accepted parameters: help_locale, include_check_help, …
```

So the substitution is NOT silent, and the claim "emitted no warning about the ignored key" was false.
`AdvisoryToolWrapper` is applied by `ToolRegistry.register`/`registerDynamicTool`, i.e. coverage is a
property of registration, and it has not changed since `99f4db0` — so build `0639` behaved the same
way. What happened is that the note is appended at the very END of the report and I read only its
head. Nothing to fix; the residual footgun (a *present but unrecognised* selector falls back to the
default project rather than refusing) is mitigated by that note and is not worth a behaviour change.

**Standing lesson, third instance.** The conclusion rested on the ABSENCE of an observation — see
`zero_build_probe_before_fix`. The whole response was only ~15k characters; one `Select-Object -Last 3`
would have settled it before the claim was written.

## 2. `delete_metadata` counts an object's own composition membership as a blocking reference

**Observed.** `delete_metadata` on a freshly adopted `CommonModule` was refused with

```
[METADATA_DELETE_CONFLICT] Удаление top-level объекта без рефакторинга отключено:
CommonModule.OrphanAdoptProbe. Найдены ссылки. Примеры:
CommonModule.OrphanAdoptProbe#source, Configuration#commonModules.
```

Both cited references belong to the object itself: `#source` is its own module, and
`Configuration#commonModules` is its own entry in the configuration composition.

**The open question is now answered — and the defect is worse than "adopted objects".** Both probes
that this file asked for were run on `0712`:

| Probe | Result |
|---|---|
| `CommonModule` created via the NORMAL `create_metadata` path, deleted without `force` | refused, same two references |
| `SessionParameter` (no module, no children, no user), created normally, deleted without `force` | refused, citing **only** `Configuration#sessionParameters` |

So it is not an adoption artefact and not module-specific: a brand-new, reference-free object of a
kind that owns nothing at all is refused, and the ONE reference cited is its own registration.

**Root cause** (read after the probes, `EdtMetadataService.ensureNoIncomingReferences`): the count was
never what decided. The gate read

```java
if (!topLevelDelete && references.total() == 0) { return; }
```

— it returns early only for CHILD deletes, so a top-level delete threw unconditionally, whatever the
reference count. The refusal was a categorical policy, and the cited "examples of references" were
garnish that made it look like a condition the caller could clear. It could not: cleaning every
reference in the configuration would still have thrown, so the message's cure ("Сначала очистите
ссылки/выполните рефакторинг, затем повторите удаление") was unreachable and `force=true` was the only
way through — teaching callers to pass the override reflexively, the opposite of a guard's purpose.

**Fixed** (see CHANGELOG Round-17). `DeleteReferenceScope` (core, unit-tested) drops two classes of
reference before counting — the object's own subtree (`#source`) and its own composition membership in
`Configuration` (`content` + `TopLevelCollections.configurationTag(kind)`) — and the gate now lets the
count decide for top-level objects too. The filter deliberately names the composition feature instead
of exempting the whole `Configuration`: `Configuration#defaultRoles`, `#defaultLanguage` and the like
are real users that the delete does NOT clean up, so they must keep refusing, and that is pinned by
tests.

### 2a. Open, narrower: a bare `CommonModule` also demands `recursive=true`

Separate gate, not fixed. `hasNestedMetadataChildren` walks EVERY containment feature, so the
`CommonModule` created seconds earlier reported nested children and needed `recursive=true` on top of
`force=true`. The `SessionParameter` did NOT (it deleted with `recursive=false`), so this is specific
to kinds that own something intrinsic — its module, most likely, but that was **not** established.

Deliberately not fixed blind: excluding the wrong containment would silently allow a real child to be
deleted without `recursive`. Instead the refusal now NAMES what it found (`Found: feature(Type),
feature×N`), which is the better message anyway and makes the next live delete of a `CommonModule`
identify the feature by itself — no diagnostic-only build round needed.

#### ANSWERED live 2026-08-10 on build `0.1.7.20260810-1011`

The self-identifying message did its job on the first try. Deleting a freshly created
`CommonModule.EnvExplicitProbe` without `recursive`:

```
[METADATA_DELETE_CONFLICT] Metadata object has nested children. Use recursive=true:
CommonModule.EnvExplicitProbe. Found: contextDef(ContextDef)
```

So the blocking containment is **`contextDef` (`ContextDef`)** — the module's derived context object,
**not** the BSL source, which was the standing guess. Worth keeping in mind before any future
exemption: `ContextDef` is derived data EDT rebuilds, so it is a plausible exemption candidate, but a
`CommonModule` carrying only that is still not proof that no kind ever owns a `contextDef` worth
protecting. `recursive=true` then deleted it cleanly, as did the same call on the second probe.
