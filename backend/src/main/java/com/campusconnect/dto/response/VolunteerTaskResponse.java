package com.campusconnect.dto.response;

import com.campusconnect.entity.enums.VolunteerTaskPriority;
import com.campusconnect.entity.enums.VolunteerTaskStatus;

import java.time.Instant;

/** A volunteer task with its event context. */
public record VolunteerTaskResponse(
        Long id,
        Long volunteerId,
        String volunteerName,
        Long eventId,
        String eventTitle,
        String assignedByName,
        String title,
        String description,
        String instructions,
        String location,
        VolunteerTaskPriority priority,
        Instant startTime,
        Instant endTime,
        VolunteerTaskStatus status,
        Instant startedAt,
        Instant completedAt,
        String completionNotes,
        Instant createdAt
) {
}
