# Known divergences from Actual

Differences between this port and the pinned Actual v26.10.0 oracle that the
parity tests excuse explicitly. Every entry names the cases it covers; a listed
case that stops showing its divergence fails the test, so fixes are noticed.

| ID | Domain | Source | Summary |
|---|---|---|---|
| OP-32 | budget-month | Actualist (intended) | A move or cover writes no "Reassigned … from … on …" month note, where Actual writes one. Only the note cell differs. |
| OP-86 | transactions | Actualist (intended) | Category placement on two transfer shapes: off-budget to on-budget puts the chosen category on the on-budget leg; on-budget to on-budget never writes the category Actual writes and then clears. Final row state matches in the second shape. |
| AND-01 | budget-month | Inherited from Actualist, found by this port | A refused hold (no money available) refuses the whole action. Actual's UI dispatches `reset-income-carryover` before `hold-for-next-month`, so in Actual the income-carryover reset commits even when the hold is refused. Observable in `generated-004-2027-11` and `generated-057-2026-11`. Actualist's replay attributes these rows to OP-32 through its cascade rule, so it is not registered there. |

Oracle-only budget-month cases not replayed (handlers neither Actualist nor this
port implements): `copy-previous-month`, `set-zero`, `set-3month-avg`,
`cover-overbudgeted`, `copy-until-year-end`, `tracking-move-money-oracle-only`.
