# A fresh CommonModule: what the "staleness" report actually is

Probe of feedback `2026-08-08-get-diagnostics-new-module-context-staleness-no-reindex.md`, run on
`workspace-sandbox` / `TestConfiguration`, build `0.1.7.20260810-0712`, before writing any code. The
note's own mechanism did not reproduce; two other things did, and one of them is ours.

## Refuted: the BSL context of a brand-new module is not "all contexts"

The note says `get_diagnostics` reports a just-created module's BSL-LS context as client+server while
`edt_metadata_details` shows the restricted flags. Created `CommonModule.StalenessProbe` with
`server=true, clientManagedApplication=false`, materialised `Module.bsl` calling the client-only
`ShowMessageBox`, and asked immediately:

```
line 3: Procedure or function 'ShowMessageBox' is not defined [Server]
```

The context was resolved as `[Server]` — restricted, correct, same session, no re-save, no re-import.
So the "settle only after a workspace rebuild" caveat the note asks us to document would be false as
written. Whatever BF-9000/BF-10381 hit, it was not the BSL context of a new module going wide.

## Confirmed, ours: `create_metadata kind=CommonModule` creates a module EDT calls invalid

`create_metadata` with **no** `properties` leaves every environment flag false. EDT answers on the
spot with five errors:

```
md-legacy-emf-check ×4
  Invalid property "clientManagedApplication" of the common module "StalenessProbeBare".
    No one environment is not set                      (same for clientOrdinaryApplication,
                                                        server, externalConnection)
common-module-type
  Common module for type "Server module" has incorrect settings:
    Client ordinary application, Server, External connection
```

A common module with no environment at all is invalid by the platform model itself
(`md-legacy-emf-check` is EDT core, not a code-style rule). So every CommonModule created through the
MCP without explicit flags is born broken, and the caller has to know to repair it.

`server=true` alone removes the four core errors but not the code-style one: EDT's canonical "Server
module" is `clientOrdinaryApplication=true, server=true, externalConnection=true`, everything else
false (read off `com.e1c.v8codestyle.md.CommonModuleTypes.SERVER` in
`com.e1c.v8codestyle.md_0.7.0.v20260218-1215.jar`). Setting exactly those two extra flags cleared the
diagnostic immediately, and the `.mdo` recorded them — so the ecore defaults really are false, the
serializer is honest, and `update_metadata` writes what it is told.

How the check decides, for anyone touching this later
(`com.e1c.v8codestyle.md.commonmodule.check.CommonModuleType`): it builds the module's flag map, and
if that map equals **any** known `CommonModuleTypes` map it returns silently. Only otherwise does it
infer the expected type from the module's **name** (`findClosestTypeByName`) and report the features
that differ. That is why the message reads like a disagreement about flag values when it is nothing of
the kind — it is a comparison against a canonical type, not a reading of the object.

## Confirmed, EDT's: a marker can be missing, and `0 errors` looks exactly like "clean"

`CommonModule.OK` (pre-existing, `server=true` only) violates `common-module-type` just as my probe
did. It nevertheless reported **0 errors, 0 warnings, 0 info** twice — including a call with
`wait_ms=5000` — and stayed clean across one `update_metadata`. Only after a second mutation
round-trip (`unset comment`) did the marker appear, with `OK.mdo` byte-identical to how it started.

So the lag the feedback note felt is real, but the dangerous direction is the opposite of the one it
describes: not a wrong marker on a new object, a **missing** marker on an old one. The tool's own
CAUTION ("a sudden drop to 0 usually means EDT is still recalculating markers — not a clean file")
holds more widely than it claims: an object nobody has touched can sit indefinitely unvalidated for a
rule it breaks, and the answer is indistinguishable from a clean scan.

Not turned into a `force_reindex` parameter here: what would need forcing is EDT's own validation
scheduling, and nothing in this probe shows a supported lever for it — a re-validation happened only
as a side effect of mutating the object. Recorded rather than guessed at.

## Open

Not needed for the fix, so left alone: why `findClosestTypeByName` yields a type that tolerates
`server`-only for some names. Both `OK` and `StalenessProbe` carry the same flags and the same
project script variant, and both are ultimately reported — so the name path is not what made `OK` look
clean, the missing marker was.
