package com.thinklab.domain.exception;

/** Domain Exception: no approval band with that id in the organisation (another tenant's is the same as none). RFC 7807 mapping: HTTP 404 Not Found. */
public class ApprovalBandNotFoundException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00404";

    public ApprovalBandNotFoundException(String message) {
        super(ERROR_CODE, message);
    }
}
