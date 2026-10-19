package com.thinklab.domain.exception;

/** Domain Exception: a REQUESTER tried to use procurement (staff only, ADR-034). RFC 7807 mapping: HTTP 403 Forbidden. */
public class ProcurementAccessDeniedException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00403";

    public ProcurementAccessDeniedException(String message) {
        super(ERROR_CODE, message);
    }
}
