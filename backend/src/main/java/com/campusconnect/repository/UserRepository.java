package com.campusconnect.repository;

import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    /**
     * Passwordless login by phone. Phone is not a unique column (it is optional and unverified),
     * so this returns the earliest-created match deterministically rather than throwing on
     * duplicates. Email remains the reliable identifier; this is a best-effort convenience.
     */
    Optional<User> findFirstByPhoneOrderByIdAsc(String phone);

    boolean existsByEmail(String email);

    Optional<User> findByVerificationToken(String verificationToken);

    Optional<User> findByResetToken(String resetToken);

    Optional<User> findByOtpChallengeToken(String otpChallengeToken);

    long countByRole(Role role);

    /**
     * Admin directory search: optional free-text match on name/email and an
     * optional role filter. Both parameters are nullable — a null means "any".
     */
    @Query("""
            select u from User u
            where (:q is null or lower(u.fullName) like lower(concat('%', :q, '%'))
                                or lower(u.email)    like lower(concat('%', :q, '%')))
              and (:role is null or u.role = :role)
            """)
    Page<User> search(@Param("q") String q, @Param("role") Role role, Pageable pageable);
}
