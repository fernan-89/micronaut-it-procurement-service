# micronaut-it-procurement-service

BIAN-aligned Service Domain **it-procurement** (Control Record: `PurchaseOrder`), port `8114`.

A slice of Journey 15 (breadth): a **registry of purchase orders**. A `PurchaseOrder` is an order for a vendor, in one currency, made of lines. It is drafted,
sent for approval to the shared **workflow approval service**, placed with the vendor once approved, and **received in part or in whole** by people who state
what arrived. The registry says, **when you ask**, how much of an order has arrived and whether the delivery is late.

## What it guarantees, and what it does not

- **It only records** (ADR-030): it never calls a vendor, a bank or a store and holds no credential. The vendor is a vendor registry id and is **not checked**.
- **Lines are fixed lists and identifiers** (ADR-030): a category (`HARDWARE`, `SOFTWARE`, `CLOUD`, `SERVICES`, `SUPPLIES`, `OTHER`), an item identifier (a part
  number or SKU, never a sentence), a quantity and a unit price. **One currency per order** (`BRL`, `USD`, `EUR`), no conversion; money in minor units, at
  most two decimal places.
- **Approval is the shared service's** (ADR-032): `control/submit` files the request on the approval service against the configured policy, and
  `approval/capture` forwards one approver's decision and reads the outcome back at once. No comment travels.
- **What arrived, and whether it is late, are judged when read** (ADR-031), never stored: `fulfilment` (`NOT_ORDERED`, `NONE`, `PARTIAL`, `COMPLETE`) and `late`.
- **Receipts are guarded atomic writes** (ADR-033): the same line and delivery reference is the same receipt (200), another quantity for it is a 409, a receipt can
  never exceed what is still to arrive (twenty receipts of one unit against five to arrive leave exactly five), and a placed order that received goods cannot be cancelled.
- **Staff only** (ADR-034): every route refuses a `REQUESTER` with 403 `ERR-PRC-00403`; another tenant's order answers 404. There is no delete (an order is cancelled).
- **Not here yet:** a budget per cost centre, amount bands for the approval, a self-service view for the requester, linking a receipt to an asset or a licence,
  vendor price checks, telling someone when an order is late (a later pairing with alerting).

## BIAN Behavior Qualifier Contract

`X-Tenant-Id` is mandatory on every call; `X-Executor` is mandatory on the actions; `X-Role` is optional and, with platform security on, comes from the verified token.

| Behavior Qualifier | Route |
|---|---|
| initiate | `POST /it-procurement/v1/initiate` `{"reference":"PO-2026-001","vendorId":"<uuid>","requesterId":"<uuid>","currency":"BRL","expectedOn":"2026-11-30","lines":[{"category":"HARDWARE","item":"LAP-14","quantity":10,"unitPrice":1500.00}]}` |
| retrieve | `GET /it-procurement/v1/{id}/retrieve` (judged now) |
| retrieve (collection) | `GET /it-procurement/v1/retrieve?status=&vendorId=&currency=&fulfilment=&late=&changedWithinDays=&limit=` (newest first, 100 unless asked, at most 500; `changedWithinDays` 1 to 365 keeps the orders last changed within that many days) |
| update | `PUT /it-procurement/v1/{id}/update` `{"vendorId":"<uuid>","expectedOn":null,"lines":[...]}` (a draft only; all the lines again) |
| control/submit | `PUT /it-procurement/v1/{id}/control/submit` (DRAFT -> PENDING_APPROVAL; files the approval) |
| approval/capture | `PUT /it-procurement/v1/{id}/approval/capture` `{"approverId":"<uuid>","outcome":"APPROVE"}` (answers the order after the decision) |
| control/place | `PUT /it-procurement/v1/{id}/control/place` (APPROVED -> ORDERED) |
| control/cancel | `PUT /it-procurement/v1/{id}/control/cancel` (final) |
| receipt/initiate | `POST /it-procurement/v1/{id}/receipt/initiate` `{"lineNo":1,"deliveryRef":"DN-4711","quantity":4}` (201 new, 200 the same again) |
| receipt/retrieve | `GET /it-procurement/v1/{id}/receipt/retrieve` |
| audit-log/retrieve | `GET /it-procurement/v1/{id}/audit-log/retrieve` |

```
order:   DRAFT -> PENDING_APPROVAL -> APPROVED -> ORDERED (receiving: NONE -> PARTIAL -> COMPLETE, judged when read)
                                   -> REJECTED (final)
         CANCELLED (final) from DRAFT, PENDING_APPROVAL, APPROVED, or ORDERED while nothing was received
receipt: a fact (line, delivery reference, quantity), idempotent, never edited
```

## Errors (RFC 7807, `error_code`)

| Code | HTTP | When |
|---|---|---|
| `ERR-PRC-00403` | 403 | A REQUESTER used procurement (ADR-034) |
| `ERR-PRC-00404` | 404 | No such order in the organisation |
| `ERR-PRC-00409` | 409 | A reference already used; an illegal transition; a receipt over what is left; a delivery reference reused with another quantity; a decision the approval service refused |
| `ERR-PRC-00502` | 502 | The approval service could not be reached or refused, or no approval policy is configured (ADR-032) |
| `ERR-VALIDATION-00400` | 400 | A value outside a list, an identifier that does not match, a price with three decimals, a bad day |

## Run, test, configure

```bash
./gradlew run                 # port 8114, MongoDB at MONGODB_URI (default mongodb://localhost:27017/thinklab_it_procurement_db)
./gradlew check               # unit tests + 100% line and branch gate + integration tests (Testcontainers, needs Docker)
```

`THINKLAB_PO_POLICY_ID` (the approval policy of purchase orders; an operator-provisioned id, there is no default: without it `control/submit` answers 502),
`WORKFLOW_APPROVAL_SERVICE_URL` (default `http://localhost:8090`), `HASH_SERVICE_URL` (default `http://localhost:8080`, the sovereign ids), `MICRONAUT_SERVER_PORT`
(default 8114), `thinklab.mongo.create-indexes` (default on). The service has no scheduler.

## Architecture decisions

001 hexagonal architecture · 005 UUID identity sovereignty and audit tracing · 013 BIAN conventions · 019 HTTP 409 for state conflicts ·
030 the registry records purchase orders · 031 what was received, and whether it is late, are judged when read · 032 approval through the shared approval service ·
033 receipts are guarded atomic writes · 034 staff only.

## License

Licensed under the [PolyForm Strict License 1.0.0](LICENSE): you may read and use this software for noncommercial purposes only. Modifying it, creating derivative works, redistributing it and any commercial use are not permitted without a separate written license. This software is not open source.
