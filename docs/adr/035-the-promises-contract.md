# ADR-035: The Promises Contract

## Status
Accepted. Implements platform ADR-001 (`thinklab-platform/docs/adr/001-the-promises-contract.md`).

## Context
The alerting service used to know this registry's fields and verdicts (which states are a broken promise, and the code of each). A rule that lived in a consumer was untested with the domain and had to be kept in step by hand.

## Decision
- The registry answers `GET /it-procurement/v1/promises/retrieve?changedWithinDays=&limit=` as the platform contract says: every broken promise and every one that changed within the window (30 days unless asked, 1 to 365), at most `limit` of each (500 unless asked, 1 to 1000), broken first.
- **The judgement lives here:** an order is **broken** (`PURCHASE_ORDER_LATE`) when it was placed, is not completely received and is past its expected day; **kept** when placed and not late, and also when cancelled (nothing is owed any more; its status is `CANCELLED`, so it never opens an alert); **not judged** in any other status. Every live order has the status `ACTIVE`. The name is the reference; an order has no asset.
- **`changedAt`:** the order's last update (a receipt, a decision, a placing and a cancellation all update it).
- Staff only, tenant-scoped, read-only.

## Consequences
- Positive: the rule is judged and tested with the domain; the consumer needs no knowledge of this registry.
- Negative: both sets are asked of the store: the placed orders expected before today, judged for lateness (up to 2,000), and the orders changed within the window; an old order that is finally received is among the second, not among the newest created.
