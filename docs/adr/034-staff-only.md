# ADR-034: Staff Only

## Status
Accepted. Same as the other registries (their ADR-032).

## Context
Purchase orders hold the organisation's spending. That is for IT staff and the tools they run, not for a requester of the service desk.

## Decision
- Every route refuses a `REQUESTER` with 403 `ERR-PRC-00403`; the role comes from `X-Role` and, with platform security on, from the verified token.
- Every route needs `X-Tenant-Id`; another tenant's order is a 404 `ERR-PRC-00404`, the same as one that does not exist.
- A value outside a fixed list, or an identifier that does not match, never appears in the answer or in the log.

## Consequences
- Positive: no cross-tenant reading.
- Negative: a requester who wants to know where a purchase stands asks staff (a self-service view is a later decision).
