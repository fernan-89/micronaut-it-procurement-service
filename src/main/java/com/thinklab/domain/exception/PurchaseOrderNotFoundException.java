package com.thinklab.domain.exception;

/** Domain Exception: no purchase order with that id in the organisation (another tenant's is the same as none). RFC 7807 mapping: HTTP 404 Not Found. */
public class PurchaseOrderNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00404";

    public PurchaseOrderNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
