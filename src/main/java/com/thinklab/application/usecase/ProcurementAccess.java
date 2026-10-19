package com.thinklab.application.usecase;

import com.thinklab.domain.exception.ProcurementAccessDeniedException;

/** Procurement is for IT staff (ADR-034): a REQUESTER is refused everywhere. */
final class ProcurementAccess {

    static final String REQUESTER_ROLE = "REQUESTER";

    private ProcurementAccess() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    static void requireStaff(String role, String operation) {
        if (REQUESTER_ROLE.equals(role)) {
            throw new ProcurementAccessDeniedException("A requester cannot " + operation + ": purchase orders are handled by IT staff.");
        }
    }
}
