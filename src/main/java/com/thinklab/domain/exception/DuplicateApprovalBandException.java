package com.thinklab.domain.exception;

/** Domain Exception: an active band of that currency already starts at that amount (ADR-036). RFC 7807 mapping: HTTP 409 Conflict. */
public class DuplicateApprovalBandException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00409";

    public DuplicateApprovalBandException(String message) {
        super(ERROR_CODE, message);
    }
}
