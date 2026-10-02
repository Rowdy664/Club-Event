package com.campusconnect.entity;

import com.campusconnect.entity.enums.CertificateType;
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

import java.time.Instant;

@Entity
@Table(
        name = "certificates",
        indexes = {
                @Index(name = "idx_cert_user", columnList = "user_id"),
                @Index(name = "idx_cert_event", columnList = "event_id"),
                @Index(name = "idx_cert_code", columnList = "certificate_code")
        },
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_cert_code", columnNames = "certificate_code"),
                // Belt-and-suspenders dedup: at most one certificate of a given type per user per
                // event. The service layer also checks existsByUserIdAndEventIdAndType, but this DB
                // constraint closes the concurrency window (e.g. auto-issue racing a manual/bulk issue).
                @UniqueConstraint(name = "uk_cert_user_event_type", columnNames = {"user_id", "event_id", "type"})
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Certificate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "event_id", nullable = false)
    private Event event;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private CertificateType type = CertificateType.PARTICIPATION;

    @Column(nullable = false, length = 180)
    private String title;

    /** Public, unguessable verification code encoded in the certificate QR. */
    @Column(name = "certificate_code", nullable = false, length = 64)
    private String certificateCode;

    @Column(nullable = false)
    private Instant issuedAt;

    /**
     * Soft-revocation flag. A revoked certificate is retained (for audit) but publicly
     * verifies as invalid and can no longer be downloaded via its public code.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean revoked = false;

    /** When the certificate was revoked, if ever. */
    private Instant revokedAt;
}
