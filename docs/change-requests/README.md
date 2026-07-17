# Change requests

Substantial features and fixes follow the change-request pattern
(inherited from TurbinePreview, where it carried ~60 CRs from CLI tool
to fielded product):

1. **Write the doc first**: `NNN-short-name.md` (next free number).
   Approach, design decisions (including the options rejected and why),
   and a concrete verification plan — the matrix of cases you will run,
   not "test it".
2. **Implement** to the doc; when reality diverges, update the doc.
3. **Run the verifications**, and record the results in the doc
   (numbers, statuses, byte counts — evidence, not adjectives). Keep any
   scripts/fixtures under `tmp/<cr>-fixtures/` so the matrix can re-run.
4. **Close out**: update README / GETTING-STARTED / CLAUDE.md where the
   change moved them, note follow-ups at the end of the doc.

The doc is the design history: when a later change asks "why is it like
this", the answer should be findable here. Amendments after field
feedback get appended to the same doc with a date, not silently edited
in.

See `000-example.md` for the skeleton.
