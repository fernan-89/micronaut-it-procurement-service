# ADR-036: Approval Bands by Amount

## Status
Accepted. Decided with the owner on 2026-11-11 (four questions, the recommended answer to each). Replaces the single policy of ADR-032.

## Context
Every purchase order was sent for approval against one policy, `thinklab.procurement.approval-policy-id`, the same for a cable and for a data centre. A small purchase should not need the quorum a large one does.

## Decision
- **Where the bands live: here**, not in the approval service. An `ApprovalBand` belongs to an organisation and says: for an order in this **currency**, from this amount **up**, use this **approval policy** (the id of a policy of the approval service). The approval service stays generic and knows nothing about money.
- **A band has only a lower bound.** The band that applies to an order is the active one, in the order's currency, with the greatest lower bound that is not above the order's total. There is no upper bound to state, so bands can neither overlap nor leave a gap above the lowest one. A band at 0 covers everything; without one, an order below the lowest bound has no band.
- **One table per currency, no conversion** (as everywhere in this service, ADR-030): a band names BRL, USD or EUR and applies to orders of that currency only.
- **No band, no submission.** If no active band covers the order (none configured, none for that currency, or the total is below the lowest bound), `control/submit` answers 502 `ERR-PRC-00502` with a fixed message and the order stays a draft; nothing is filed on the approval service. The environment variable `THINKLAB_PO_POLICY_ID` is **gone**: a deployment provisions a band at 0 instead.
- **The band is chosen at submission and kept.** `control/submit` writes the band, its policy and the total in the audit entry of the submission (the audit trail is never edited). From then on the lines cannot change (only a draft takes an update, unchanged), so the amount the approvers saw is the amount that is approved. To change the amount, cancel and draft another order.
- **Managing bands:** `band/initiate` (currency, from-amount, policy id), `band/retrieve` (the active ones, optionally one currency) and `band/{id}/control/retire` (a retired band is kept, no longer used, and its lower bound can be used again). There is no edit and no delete: to change a band, retire it and initiate another. Two active bands of one currency cannot have the same lower bound (a unique partial index is the atomic backstop). Staff only, like every route here (ADR-034).
- **Nothing in words:** a band holds numbers, a currency and an id; its audit is the executor and the moment.

## Consequences
- Positive: the quorum follows the amount; bands are data, not deployment; the approval service is unchanged.
- Negative: a deployment that never provisioned a band can no longer submit (the e2e provisioning creates the band at 0); a policy id is not checked against the approval service when a band is made (a wrong id shows up as a refused submission); changing an amount means cancelling the order.
