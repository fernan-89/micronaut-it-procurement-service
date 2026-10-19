# ADR-032: Approval Through the Shared Approval Service

## Status
Accepted. Same integration as change management (its ADR-032).

## Context
Approval is a shared capability of the platform (the workflow approval service), and the blueprint named procurement as its second client. The user chose to reuse it, with the quorum set per organisation.

## Decision
- **Sending for approval** (`control/submit`) files a new `ApprovalRequest` on the approval service against the policy in `thinklab.procurement.approval-policy-id` (an operator-provisioned id, as for change management), with subject type `PurchaseOrder`, and only then moves the order to `PENDING_APPROVAL`, guarded on the status loaded. An order that cannot go (not a draft, or no lines) is refused before anything is filed. With no policy configured, or when the approval service cannot be reached or refuses, the answer is 502 `ERR-PRC-00502` and the order stays a draft.
- **A decision** (`approval/capture`) forwards one approver's decision (`APPROVE` or `REJECT`, as that approver) and reads the resolved status back in the same response: `APPROVED` or `REJECTED` moves the order, `PENDING` (a quorum not yet reached) leaves it waiting. Only an order that is waiting takes a decision. A 409 from the approval service (an approver who is not eligible, a request already decided) is relayed as this service's own 409 with the reason it gave.
- **Nothing in words travels:** no comment is sent to the approval service or kept here.
- **The integration is synchronous HTTP**, not events, so the caller sees the outcome at once.
- If the save loses a race after the request was filed, the request stays unused in the approval service, which keeps it; nothing here decides on it.

## Consequences
- Positive: the quorum and the approvers are configured once, for every client; the order's history shows who decided.
- Negative: a single policy for all purchase orders (no amount bands yet); a decision made on the approval service directly (not through this service) is not seen until the next decision.
