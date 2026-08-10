# Two defects observed on the sandbox, 2026-08-10

Found incidentally while live-validating `create_metadata adopt_existing` (CHANGELOG Round-15).
Both observed on build `0.1.7.20260810-0639`, `workspace-sandbox`, project `TestConfiguration`.
Neither is fixed yet.

## 1. `get_diagnostics` answers about the wrong project when the selector is misspelled

**Observed.** Called with `projectName='TestConfiguration'` (the schema's actual key is `project_name`)
plus `scope=project`. The tool returned a confident, well-formed report headed
`## Diagnostics: /Accounting management` — **6402 errors from a different project** — and emitted no
warning about the ignored key. The same call with `project_name` returns the correct
`## Diagnostics: /TestConfiguration`, 17 errors.

**Why it is worse than the usual swallowed-parameter case.** Other tools now print a note naming the
ignored key and listing the accepted ones (`edt_metadata_details`, `list_files` and `write_file` all did
so during the same session, which is how the correct spelling was found). `get_diagnostics` does not, and
its schema documents the fallback as intended behaviour: *"if omitted, default project or workspace
diagnostics are used"*. That fallback is right for an omitted key and wrong for a **misspelled** one —
the caller asked about a specific project and got an answer about another, with nothing marking the
substitution. A caller cannot tell this apart from a correct answer.

**Cure.** Wire the unknown-parameter note into `get_diagnostics` (the mechanism already exists —
`SchemaKeyGuard`), and additionally accept `projectName` as an alias for `project_name`, the way
`edt_diagnostics` already aliases `project`/`project_name`. An alias alone is not enough: the next
misspelling would fall into the same silent fallback.

**Test note.** A source-contract assertion would not catch this — see the standing rule that such tests
do not see behaviour. Cover it as a pure function over the parameter map: unknown-key set → note text.

## 2. `delete_metadata` treats an object's own configuration membership as a blocking reference

**Observed.** `delete_metadata` on a freshly adopted `CommonModule` was refused with

```
[METADATA_DELETE_CONFLICT] Удаление top-level объекта без рефакторинга отключено:
CommonModule.OrphanAdoptProbe. Найдены ссылки. Примеры:
CommonModule.OrphanAdoptProbe#source, Configuration#commonModules.
```

Both cited references belong to the object itself: `#source` is its own module, and
`Configuration#commonModules` is its own entry in the configuration composition. Deleting it required
`force=true` **and then** `recursive=true` (the module counts as a nested child), i.e. two technical
overrides to remove an object with no external users at all.

**Open question — do not assume the answer.** Every top-level object is referenced from
`Configuration#<collection>` by construction, so this guard may make the non-`force` top-level delete
path unreachable in general, not just for adopted objects. That was **not** verified: checking it would
mean deleting a pre-existing object in the sandbox, and `edt_validate_request` is no help because it does
not run the reference scan (it returned `valid:true` for a `create_metadata` whose FQN was already taken).
Verify by adding a top-level object through the normal `create_metadata` path and deleting it without
`force`, then decide:

* if the guard fires there too, the reference scan must exclude self-references (`#source`) and the
  object's own composition membership before counting, and the message should stop advertising `force` as
  the routine cure;
* if it does not, the adoption path leaves something extra behind and that is the real bug.
