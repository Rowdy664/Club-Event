package com.campusconnect.entity;

import com.campusconnect.entity.enums.VolunteerTaskPriority;
import com.campusconnect.entity.enums.VolunteerTaskStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Lob;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A discrete task assigned to a volunteer for an event (e.g. "Registration
 * desk", "QR check-in"). Driven through PENDING &rarr; IN_PROGRESS &rarr;
 * COMPLETED by the volunteer; created/prioritised by a coordinator or member.
 */
@Entity
@Table(
        name = "volunteer_tasks",
        indexes = {
                @Index(name = "idx_vtask_volunteer", columnList = "volunteer_id"),
                @Index(name = "idx_vtask_event", columnList = "event_id"),
                @Index(name = "idx_vtask_status", columnList = "status")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VolunteerTask extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "volunteer_id", nullable = false)
    private Volunteer volunteer;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_by")
    private User assignedBy;

    @Column(nullable = false, length = 150)
    private String title;

    @Lob
    @Column(columnDefinition = "TEXT")
    private String description;

    /** Free-text instructions the volunteer should follow to complete the task. */
    @Lob
    @Column(columnDefinition = "TEXT")
    private String instructions;

    @Column(length = 200)
    private String location;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private VolunteerTaskPriority priority = VolunteerTaskPriority.MEDIUM;

    private Instant startTime;

    private Instant endTime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @Builder.Default
    private VolunteerTaskStatus status = VolunteerTaskStatus.PENDING;

    /** When the volunteer actually started the task (start action). */
    private Instant startedAt;

    /** When the volunteer actually completed the task (complete action). */
    private Instant completedAt;

    @Lob
    @Column(name = "completion_notes", columnDefinition = "TEXT")
    private String completionNotes;
}
