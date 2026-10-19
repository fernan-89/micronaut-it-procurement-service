# ADR-033: Receipts Are Guarded Atomic Writes

## Status
Accepted. Same discipline as the other registries (their ADR-033).

## Context
Several people (or tools) may receive goods against one order at the same time, and may repeat a receipt. More must never be received than was ordered, and every change to an order must be safe with several instances.

## Decision
- **A receipt is a fact** (`receipt/initiate`): that much of a line arrived, with the **delivery reference** (an identifier: a delivery note number, never a sentence), said by a person or a tool. It is never edited and never deleted, and it is kept inside the order (at most 200 per order).
- **Idempotent:** the same line and delivery reference with the same quantity is the same receipt (200 with the stored one); a new one is 201. The same delivery reference for the same line with **another** quantity is a 409: a delivery note number cannot be reused.
- **One atomic update in the store** applies a receipt: the order is still `ORDERED`, the line has at least that much still to arrive, the same line and delivery reference was not received before, and the receipts are not full. The quantity still to arrive of the line is decreased in the same update. So twenty people receiving one unit each of a line with five to arrive leave **exactly five** applied, and twenty receipts of the same delivery reference leave **one**.
- When the store refuses (someone got there first), the order is read once more and judged again: either the receipt is found (200) or the exact refusal is given (409, saying how many are still to arrive). A second refusal is a 409 asking the caller to retry.
- **Every other change is guarded on the status loaded** and appends its audit entry in the same update. The lines are written only for a draft, because receipts change them at the same time once the order is placed; **cancelling a placed order also needs that no receipt exists**, checked by the same update.
- A **reference is unique per organisation** (a unique index, the atomic backstop of a duplicate); the list has an index on organisation, status and creation time.

## Consequences
- Positive: no overbooking of a delivery, safe with several instances and with retries.
- Negative: a receipt cannot be undone: a mistake is corrected by the next delivery or by a new order.
