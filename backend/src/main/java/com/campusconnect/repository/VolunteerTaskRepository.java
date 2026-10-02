package com.campusconnect.repository;

import com.campusconnect.entity.VolunteerTask;
import com.campusconnect.entity.enums.VolunteerTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VolunteerTaskRepository extends JpaRepository<VolunteerTask, Long> {

    List<VolunteerTask> findByVolunteerId(Long volunteerId);

    List<VolunteerTask> findByVolunteerIdIn(List<Long> volunteerIds);

    List<VolunteerTask> findByVolunteerIdAndStatus(Long volunteerId, VolunteerTaskStatus status);

    List<VolunteerTask> findByEventId(Long eventId);

    long countByVolunteerIdAndStatus(Long volunteerId, VolunteerTaskStatus status);
}
