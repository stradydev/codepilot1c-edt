# BF-11156's three "discovery gaps": two were real, the third already had a tool

Probe of feedback `2026-08-08-bf11156-diagnostics-and-metadata-discovery-gaps.md`, run on
`workspace-sandbox` / `Accounting management`, build `0.1.7.20260810-0712`. Items 1 and 2 were fixed;
item 3 asked for a new tool and turned out not to need one.

## Item 3 — refuted: `edt_metadata_details` already answers both halves

The ask was a `metadata_exists` / `enum_members` helper, because confirming that an Enum is absent had
meant globbing all 201 enum `.mdo` files.

**Existence** — one call, and the miss is explicit rather than an empty answer:

```
edt_metadata_details projectName="Accounting management" objectFqns=["Enum.ZzzNoSuchEnum"]
  exists  false
  message Object not found in project 'Accounting management': the kind 'Enum' IS supported and was
          searched, so no object of that kind carries this name. …
```

**Values** — the same call with `full=true`:

```
### Children
- `EnumValue` Regulated      (`Enum.AccountsTypes.enumValues`) — uuid=62db491a-…, name=Regulated
- `EnumValue` Хозрасчетный   (`Enum.AccountsTypes.enumValues`) — uuid=e5f5752f-…, name=Хозрасчетный
```

Without `full` the answer is properties only — no children at all. So nothing was missing except the
knowledge that `full` is the flag that reveals containment, and the tool's own description worked
against learning it: it advertised "properties, **children**, forms, and modules" unconditionally,
while children in fact require `full=true`. That is what got fixed — the description now says which
flag adds children and that the tool also answers existence. No new tool.

Worth noting for the next reader: `exists:false` arrives on the **success** channel as a rendered
`MdObject` section, not as an error. A caller scanning only for a failure will read a miss as a hit.

## Items 1 and 2 — real, fixed

* `include_check_help=true` dropped rules whose bundle ships no HTML description without a word. Now
  named in one aggregate entry (`CheckHelpDetails`, core, unit-tested).
* A collapsed group elided variants past a flat cap of three, which for `form-event-regions` hid the
  exact required region name the caller was after. The cap is now on characters
  (`DiagnosticGroupSamples`), so short variants all survive while the block cannot exceed what the
  three-message cap already allowed.

## Still open from item 1

The second half of item 1 — a mapping from EDT `bsl-legacy-*` ids to their BSL-LS canonical keys, so
`explain_diagnostics` or a skip-check key can be looked up — is untouched. It is a research task
(three ids per check; see the `edt_su_check_codes_extraction` memory note), not a formatting fix, and
the aggregate entry at least stops the silence from looking like "nothing to know".
