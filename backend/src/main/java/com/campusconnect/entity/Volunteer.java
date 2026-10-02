package com.campusconnect.entity;

import com.campusconnect.entity.enums.VolunteerStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A user's volunteer profile within a single club. A user may volunteer for
 * more than one club, so uniqueness is scoped to (user, club). The coordinator
 * of the club approves the profile (status ACTIVE) before it can be assigned.
 */
@Entity
@Table(
        name = "volunteers",
        indexes = {
                @Index(name = "idx_vol_user", columnList = "user_id"),
                @Index(name = "idx_vol_club", columnList = "club_id"),
                @Index(name = "idx_vol_status", columnList = "status")
        },
        uniqueConstraints = @UniqueConstraint(name = "uk_vol_user_club", columnNames = {"user_id", "club_id"})
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Volunteer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "club_id", nullable = false)
    private Club club;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private VolunteerStatus status = VolunteerStatus.PENDING;

    /** Comma-separated free-text skills (e.g. "Photography, First Aid, Anchoring"). */
    @Column(length = 500)
    private String skills;

    /** Free-text availability note (e.g. "Weekends, evenings after 5pm"). */
    @Column(length = 500)
    private String availability;

    /** Denormalised running total of hours worked, kept in sync on check-out. */
    @Column(name = "total_hours", nullable = false)
    @Builder.Default
    private double totalHours = 0d;

    /** True when the coordinator has elevated this volunteer to a lead. */
    @Column(name = "volunteer_lead", nullable = false)
    @Builder.Default
    private boolean volunteerLead = false;
}
