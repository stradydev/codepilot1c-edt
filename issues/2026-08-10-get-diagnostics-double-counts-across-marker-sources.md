# `get_diagnostics` counts one defect twice when both marker sources see it

**Found:** 2026-08-10 live on sandbox build `0.1.7.20260810-1011`, while validating the baseline
diff (Round-18). **Status:** OPEN, reproduced, cause located. Not a regression — pre-existing.
**Severity:** medium. Nothing is missing or wrong; the *counts* are inflated, and counts are exactly
what a caller uses to answer "did my change break anything".

## Reproduction

One deliberate BSL error (a call to an undefined procedure) in
`TestConfiguration/src/CommonModules/OK/Module.bsl`, then a project scan:

| Call | Total |
|---|---|
| `scope=project`, defaults | **2 errors** — the same message on the same line, listed twice |
| `scope=project`, `include_runtime_markers=false` | **1 error** |

The two renderings of the one defect differ:

```
- line 3: Procedure or function 'X' is not defined [Server]  [undefined-function-or-procedure]
  location: line 3
  object: CommonModule.OK.Module
- line 3: Procedure or function 'X' is not defined [Server]
```

The second has no rule code and no location/object — it is the **workspace-attached** marker; the
first is the **runtime** marker. Both describe one defect. The same doubling is visible in the
pre-existing noise of that project (a `Syntax error. Mismatched input ";"` appears once coded and
once bare), so any recorded count from an unfiltered scan is suspect — including the "3 errors"
noted for the Round-16 `path_contains` check.

## Cause

`EdtDiagnosticsCollector` funnels both sources through one `seen` set but keys them differently, so a
collision is impossible by construction:

* workspace markers — `markerPath + ":" + line + ":" + charStart + ":" + message`
* runtime markers — `markerPath + ":" + checkId + ":" + message + ":" + location + ":" + objectPresentation`

Two problems at once: the shapes differ, and the paths differ too (the runtime branch uses
`marker.getProject()`'s path where the workspace branch uses the file's). Even an identical shape
would not match today.

Note `scope=file` already passes a shared `seen` into `collectRuntimeFileMarkers` — so the file path
may already dedup where the project path does not. Worth checking before assuming both are broken.

## What a fix has to be careful about

Do **not** simply key both on `path + line + message`: two genuinely different checks can produce the
same message on the same line, and merging them would hide one. A safer shape is a
cross-source pass that merges only when path + line + message match **and** one side carries no
`checkId` (the workspace marker is the un-attributed copy of an attributed runtime one). Then keep the
attributed copy — it is the one that carries the rule code the caller needs.

Whatever the shape, the fix needs a live check on a project where both sources are populated: this is
not observable in the test runtime, which has neither marker source.
