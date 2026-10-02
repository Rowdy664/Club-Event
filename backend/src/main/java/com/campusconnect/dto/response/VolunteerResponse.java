package com.campusconnect.dto.response;

import com.campusconnect.entity.enums.VolunteerStatus;

import java.time.Instant;

/** A volunteer profile with its owning user and club, for the volunteer and coordinators. */
public record VolunteerResponse(
        Long id,
        Long userId,
        String fullName,
        String email,
        String studentId,
        String department,
        String phone,
        String profilePhotoUrl,
        Long clubId,
        String clubName,
        VolunteerStatus status,
        String skills,
        String availability,
        double totalHours,
        boolean volunteerLead,
        Instant createdAt
) {
}
