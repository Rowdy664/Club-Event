package com.campusconnect.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A user applying to volunteer for a club. */
public record VolunteerApplyRequest(
        @NotNull Long clubId,
        @Size(max = 500) String skills,
        @Size(max = 500) String availability
) {
}
