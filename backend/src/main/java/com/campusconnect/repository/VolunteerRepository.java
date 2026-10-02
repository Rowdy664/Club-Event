package com.campusconnect.repository;

import com.campusconnect.entity.Volunteer;
import com.campusconnect.entity.enums.VolunteerStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VolunteerRepository extends JpaRepository<Volunteer, Long> {

    /** A user's volunteer profiles across all clubs. */
    List<Volunteer> findByUserId(Long userId);

    Optional<Volunteer> findByUserIdAndClubId(Long userId, Long clubId);

    boolean existsByUserIdAndClubId(Long userId, Long clubId);

    /** Any active volunteer profile for the user — used to gate volunteer-only actions. */
    Optional<Volunteer> findFirstByUserIdAndStatus(Long userId, VolunteerStatus status);

    List<Volunteer> findByClubId(Long clubId);

    List<Volunteer> findByClubIdAndStatus(Long clubId, VolunteerStatus status);

    long countByClubIdAndStatus(Long clubId, VolunteerStatus status);
}
