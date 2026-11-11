package com.thinklab.application.mapper;

import com.thinklab.application.dto.response.ApprovalBandResponse;
import com.thinklab.domain.model.ApprovalBand;
import com.thinklab.domain.model.Money;

public final class ApprovalBandMapper {

    private ApprovalBandMapper() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    public static ApprovalBandResponse toResponse(ApprovalBand band) {
        return new ApprovalBandResponse(band.getId(), band.getOrganisationId(), band.getCurrency().name(), Money.toMajor(band.getFromMinor()), band.getPolicyId(),
                band.getStatus().name(), band.getCreatedAt(), band.getCreatedBy(), band.getRetiredAt(), band.getRetiredBy());
    }
}
