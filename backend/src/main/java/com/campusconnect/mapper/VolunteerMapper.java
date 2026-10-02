package com.campusconnect.mapper;

import com.campusconnect.dto.response.VolunteerResponse;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.User;
import com.campusconnect.entity.Volunteer;

public final class VolunteerMapper {

    private VolunteerMapper() {
    }

    public static VolunteerResponse toResponse(Volunteer volunteer, long taskCount, long completedTaskCount) {
        if (volunteer == null) {
            return null;
        }
        User user = volunteer.getUser();
        Club club = volunteer.getClub();
        return new VolunteerResponse(
                volunteer.getId(),
                user != null ? user.getId() : null,
                user != null ? user.getFullName() : null,
                user != null ? user.getEmail() : null,
                user != null ? user.getStudentId() : null,
                user != null ? user.getDepartment() : null,
                user != null ? user.getPhone() : null,
                user != null ? user.getProfilePhotoUrl() : null,
                club != null ? club.getId() : null,
                club != null ? club.getName() : null,
                volunteer.getStatus(),
                volunteer.getSkills(),
                volunteer.getAvailability(),
                volunteer.getTotalHours(),
                volunteer.isVolunteerLead(),
                volunteer.getCreatedAt()
        );
    }
}
