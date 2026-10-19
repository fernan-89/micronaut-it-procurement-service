package com.thinklab.domain.exception;

/** Domain Exception: the approval service could not be reached or refused (ADR-032). Nothing of its answer is repeated, except the reason of a refused decision (409). RFC 7807 mapping: HTTP 502 Bad Gateway. */
public class ApprovalUnavailableException extends BusinessException {

    private static final String ERROR_CODE = "ERR-PRC-00502";

    public ApprovalUnavailableException(String message) {
        super(ERROR_CODE, message);
    }
}
