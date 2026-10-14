# Known divergences from Actual

Differences between this port and the pinned Actual v26.10.0 oracle that the
parity tests excuse explicitly. Every entry names the cases it covers; a listed
case that stops showing its divergence fails the test, so fixes are noticed.

Unless marked otherwise, each is an **intended** difference registered by
Actualist (`config/parity/known-divergences.json` at the reference commit) and
excused here by exactly the same cases and paths as Actualist's own replays.

| ID | Domain | Summary |
|---|---|---|
| OP-22 | account lifecycle | Rename trims outer whitespace and writes nothing when the trimmed name is unchanged; Actual's handler writes every submitted value. |
| OP-24 | templates | A category with a null stored `goal_def` but a `#template` note gets no budget at apply; Actual regenerates `goal_def` from the note first. Excused only in the as-stated replay; the replay with stored definitions is strict. |
| OP-25 | templates | A stored definition that no longer matches its note refuses the whole apply. |
| OP-32 | budget month | A move or cover writes no "Reassigned … from … on …" month note. Only the note cell differs. |
| OP-51 | schedules | Skipping a one-time schedule is refused; Actual accepts it and changes nothing. |
| OP-52 | schedules | A new schedule's first next date is searched from the creation day; Actual searches from the day before in time zones behind UTC. |
| OP-53 | schedules | Creating a schedule on a closed account is refused. |
| OP-58 | import reconcile | A matched row in an off-budget account never takes a supplied category. |
| OP-59 | import reconcile | First-sync Starting Balance amount subtracts planned inserts, not every downloaded row. |
| OP-60 | import reconcile | The Starting Balance row is dated at the earliest downloaded day. |
| OP-61 | import reconcile | With reimport-deleted off, a download matching a deleted row is skipped. |
| OP-62 | import reconcile | Children of an id-matched split parent are excluded from fuzzy matching for the batch. |
| OP-66 | import reconcile | Provider payee names are trimmed before a payee is created. |
| OP-67 | import reconcile | Among equally good stored matches, the row stored first wins. |
| OP-68 | import reconcile | Two live rows with the same provider id: a different one is updated than in Actual. |
| OP-70 | transaction commands | Duplicating a split whose children do not add up is refused. |
| OP-71 | transaction commands | Merging a selected split child is refused. |
| OP-72 | transaction commands | Merging a split with no children or with a split error is refused. |
| OP-73 | transaction commands | Merging a transfer with a missing or non-reciprocal peer is refused. |
| OP-74 | transaction commands | Keeping a split parent and dropping a simple row is refused. |
| OP-75 | transaction commands | A refused merge writes nothing (Actual has already cleared four `transferred_id` cells). |
| OP-76 | transaction commands / undo | Undo after a later edit to any row the gesture touched (for a merge, any row of its graph) is refused; Actual's undo overwrites the later edit. Applies to every undo in the app. |
| OP-78 | transaction queries | Free-text search is flat across split children. |
| OP-79 | transaction queries | A deleted or missing parent hides its orphan child in the all-rows read. |
| OP-86 | transactions | Category placement on two transfer-create shapes. Final row state matches in one of them. |
| AND-02 | account lifecycle / undo | **Found by this port.** Actual closes an account with a balance in two batches, so undoing it nulls the source leg's `category` and `transferred_id` cells; this port closes in one batch and tombstones them instead. The leg is tombstoned either way. Case `close-positive-on-to-on-history`. |
| AND-01 | budget month | **Inherited from Actualist, found by this port.** A refused hold refuses the whole action; Actual's UI commits `reset-income-carryover` before the hold fails. Observable in `generated-004-2027-11` and `generated-057-2026-11`; Actualist's replay attributes these rows to OP-32 through its cascade rule. |

## Beyond Actualist

- The five bulk month actions (copy previous month, set zero, 3-month average,
  copy to year end, cover overbudgeted) are oracle-only in Actualist. This port
  implements them from Actual's own source and replays those five cases strictly.
- Reconciliation: unlocking a child of a locked parent follows Actual (no write)
  rather than Actualist's OP-19.
- Transfer edits do not propagate the date to the paired row, as Actual's
  `updateTransfer` does not.

## Not replayed

Oracle-only cases named by each manifest, exactly as Actualist does not replay
them: budget month 1 (tracking move money), rules 24, schedules 2, transaction
queries 13, import reconcile 19 (5 of them, the provider field-mapping cases, are
replayed by the SimpleFIN package instead), splits 2, account lifecycle 6 (forced
delete, GoCardless).

## Undo

Undo is a port of Actual's own cell-level undo (`server/undo.ts`), not of
Actualist's per-gesture action log. The undo halves of all 39 transaction-command
cases (outcome and rows, as Actualist compares) and the five account-lifecycle
undo observations (exact messages) are replayed.
