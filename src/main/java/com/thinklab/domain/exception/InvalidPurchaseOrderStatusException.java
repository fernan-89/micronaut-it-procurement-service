package com.thinklab.domain.exception;

/** Domain Exception: the purchase order is not in the status the change needs, a receipt would exceed what was ordered, or a delivery reference was reused with another quantity (ADR-033). RFC 7807 mapping: HTTP 409 Conflict. */
public class InvalidPurchaseOrderStatusException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00409";

    public InvalidPurchaseOrderStatusException(String message) {
        super(ERROR_CODE, message);
    }
}
