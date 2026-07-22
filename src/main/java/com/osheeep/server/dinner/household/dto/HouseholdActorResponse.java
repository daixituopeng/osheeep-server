package com.osheeep.server.dinner.household.dto;

import java.util.Set;

public record HouseholdActorResponse(String kind) {

    private static final Set<String> KINDS = Set.of(
            "ME", "PARTNER", "EXITED_MEMBER", "DELETED_MEMBER");

    public HouseholdActorResponse {
        if (!KINDS.contains(kind)) {
            throw new IllegalArgumentException("Unsupported household actor kind");
        }
    }
}
