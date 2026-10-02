package com.campusconnect.mapper;

import com.campusconnect.dto.response.VolunteerTaskResponse;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.User;
import com.campusconnect.entity.Volunteer;
import com.campusconnect.entity.VolunteerTask;

public final class VolunteerTaskMapper {

    private VolunteerTaskMapper() {
    }

    public static VolunteerTaskResponse toResponse(VolunteerTask task) {
        if (task == null) {
            return null;
        }
        Volunteer volunteer = task.getVolunteer();
        Event event = task.getEvent();
        User assignee = volunteer != null ? volunteer.getUser() : null;
        return new VolunteerTaskResponse(
                task.getId(),
                volunteer != null ? volunteer.getId() : null,
            assignee != null ? assignee.getFullName() : null,
                event != null ? event.getId() : null,
                event != null ? event.getTitle() : null,
            task.getAssignedBy() != null ? task.getAssignedBy().getFullName() : null,
                task.getTitle(),
                task.getDescription(),
            task.getInstructions(),
            task.getLocation(),
            task.getPriority(),
            task.getStartTime(),
            task.getEndTime(),
                task.getStatus(),
            task.getStartedAt(),
            task.getCompletedAt(),
            task.getCompletionNotes(),
                task.getCreatedAt()
        );
    }
}
