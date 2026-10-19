package com.thinklab.domain.exception;

/** Domain Exception: a purchase order reference already used in the organisation. RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicatePurchaseOrderException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00409";

    public DuplicatePurchaseOrderException(String message) {
        super(ERROR_CODE, message);
    }
}
