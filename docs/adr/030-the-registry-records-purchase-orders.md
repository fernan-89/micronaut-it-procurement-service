# ADR-030: The Registry Records Purchase Orders

## Status
Accepted. Same shape as the other registries of Journeys 14 and 15 (their ADR-030).

## Context
Journey 15 (breadth) lists procurement: who asked to buy what, who approved it, what was ordered and what has arrived. The user chose: the core is a **purchase order (PO) with lines and a life cycle**, approved through the **shared approval service**, in **one currency per order with no conversion**, with **people stating what arrived** (in part or in whole), and **no budget control in the first version**.

## Decision
- **The registry only records.** A `PurchaseOrder` is a reference (the organisation's own number: an identifier, up to 40 letters, digits, `.`, `_`, `/` or `-`, unique per organisation), a vendor (a vendor registry id, **not checked**: the registries stay independent), a requester (an id), a currency (`BRL`, `USD`, `EUR`, fixed once created), the day the goods are expected (optional, between today and two years ahead) and up to 50 lines. It never calls a vendor, a bank or a store, and holds no credential.
- **A line** is a category from a fixed list (`HARDWARE`, `SOFTWARE`, `CLOUD`, `SERVICES`, `SUPPLIES`, `OTHER`), an item identifier (a part number or SKU, up to 64 characters, **never a sentence**), a quantity (1 to 100,000) and a unit price. Lines are numbered 1 to n by the order.
- **Money is kept in minor units** (`long`), as in the cloud cost registry: a price is a decimal with at most two places, never negative; the total of an order is the sum of quantity times unit price and may not exceed the largest amount accepted. The registry never converts and never adds two currencies.
- **Life cycle:** `DRAFT` → `PENDING_APPROVAL` → `APPROVED` or `REJECTED` (final); `APPROVED` → `ORDERED` (placed with the vendor); `CANCELLED` (final) from a draft, while waiting, once approved, or once ordered while nothing has been received. Lines, vendor and the expected day change only in a draft; the reference, the requester and the currency never change. There is no delete.
- **Every change is a named operation with an audit trail** that records who did what and from which status to which.

## Consequences
- Positive: no vendor integration, no personal data, nothing in words that could hold a secret.
- Negative: the order does not check that the vendor exists or that the price is the contract's price; no budget (a later decision).
