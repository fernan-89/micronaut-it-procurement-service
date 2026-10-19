# ADR-031: What Was Received, and Whether It Is Late, Are Judged When Read

## Status
Accepted. Same idea as the other registries (their ADR-031).

## Context
How much of an order has arrived changes with every receipt, and "late" changes with the clock. Storing the verdict would need something to change it.

## Decision
- The registry stores no verdict. An order that is `ORDERED` is judged **`fulfilment`**: `NONE` (nothing has arrived), `PARTIAL` or `COMPLETE` (everything arrived). Any other status has `NOT_ORDERED`.
- An order is **`late`** when it is ordered, not complete, has an expected day and today is after it.
- The list can be filtered by `fulfilment` and `late`. Because these are judged, such a filter asks the store for the **placed** orders only (and, for late, only those expected before today) and then judges up to 2,000 of them, so the limit applies to what was asked for.
- A complete order stays `ORDERED`: nothing moves it, so nothing needs to.

## Consequences
- Positive: no scheduler, no stale verdict, a changed expected day changes the answer at once.
- Negative: a tenant with more than 2,000 placed orders at a time would have the oldest left out of a lateness filter.
