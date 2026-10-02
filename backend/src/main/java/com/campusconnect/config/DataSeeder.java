package com.campusconnect.config;

import com.campusconnect.entity.Club;
import com.campusconnect.entity.ClubMember;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.NotificationPreference;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.ClubRole;
import com.campusconnect.entity.enums.EventMode;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.entity.enums.MembershipStatus;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.repository.ClubMemberRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.NotificationPreferenceRepository;
import com.campusconnect.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Seeds realistic demo data on first startup (only when the DB has no users).
 * Enabled via {@code app.seed.enabled=true}. All seeded accounts share the demo
 * password below purely for local development — never enable seeding in production.
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class DataSeeder implements CommandLineRunner {

    private static final String DEMO_PASSWORD = "Password123!";

    private final UserRepository userRepository;
    private final ClubRepository clubRepository;
    private final ClubMemberRepository clubMemberRepository;
    private final EventRepository eventRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(String... args) {
        if (userRepository.count() > 0) {
                        ensureDemoAdmin();
            log.info("Seed skipped: database already contains users.");
            return;
        }
        log.info("Seeding CampusConnect demo data...");

        User admin = createUser("Platform Admin", "admin@campusconnect.local",
                Role.ADMIN, "CC0000001", "Administration", "+91-9000000000");
        User coordinator = createUser("Priya Sharma", "coordinator@campusconnect.local",
                Role.CLUB_COORDINATOR, "CC2021001", "Computer Science", "+91-9000000001");
        User member = createUser("Rahul Verma", "member@campusconnect.local",
                Role.CLUB_MEMBER, "CC2022014", "Computer Science", "+91-9000000002");
        User student = createUser("Ananya Iyer", "student@campusconnect.local",
                Role.STUDENT, "CC2023087", "Electronics", "+91-9000000003");
        User student2 = createUser("Vikram Nair", "vikram@campusconnect.local",
                Role.STUDENT, "CC2023102", "Mechanical", "+91-9000000004");
        User student3 = createUser("Sara Khan", "sara@campusconnect.local",
                Role.STUDENT, "CC2023155", "Information Technology", "+91-9000000005");
        User volunteer = createUser("Karthik Menon", "volunteer@campusconnect.local",
                Role.VOLUNTEER, "CC2023200", "Computer Science", "+91-9000000006");
        userRepository.saveAll(java.util.List.of(admin, coordinator, member, student, student2, student3, volunteer));

        // Give every demo account an all-on notification preference row (each with its own
        // unsubscribe token), matching what real users receive at registration.
        notificationPreferenceRepository.saveAll(java.util.List.of(
                NotificationPreference.defaultsFor(admin),
                NotificationPreference.defaultsFor(coordinator),
                NotificationPreference.defaultsFor(member),
                NotificationPreference.defaultsFor(student),
                NotificationPreference.defaultsFor(student2),
                NotificationPreference.defaultsFor(student3),
                NotificationPreference.defaultsFor(volunteer)
        ));

        Club coding = createClub("Coding Club",
                "A community of student developers building projects, hosting hackathons and running weekly workshops.",
                "Technology", coordinator);
        Club cultural = createClub("Cultural Society",
                "Celebrating art, music and dance across campus with festivals and open-mic nights.",
                "Arts & Culture", coordinator);
        Club robotics = createClub("Robotics Club",
                "Hands-on robotics, embedded systems and an annual bot-battle competition.",
                "Technology", coordinator);
        clubRepository.saveAll(java.util.List.of(coding, cultural, robotics));

        clubMemberRepository.saveAll(java.util.List.of(
                membership(coding, coordinator, ClubRole.COORDINATOR, MembershipStatus.ACTIVE),
                membership(coding, member, ClubRole.MEMBER, MembershipStatus.ACTIVE),
                membership(coding, student, ClubRole.MEMBER, MembershipStatus.ACTIVE),
                membership(cultural, coordinator, ClubRole.COORDINATOR, MembershipStatus.ACTIVE),
                membership(cultural, student2, ClubRole.MEMBER, MembershipStatus.ACTIVE),
                membership(robotics, coordinator, ClubRole.COORDINATOR, MembershipStatus.ACTIVE),
                membership(robotics, student3, ClubRole.MEMBER, MembershipStatus.PENDING)
        ));

        LocalDateTime now = LocalDateTime.now();

        Event hackfest = Event.builder()
                .title("HackFest 2026")
                .description("A 24-hour flagship hackathon. Form a team, build a product and pitch to judges.")
                .category("Hackathon")
                .mode(EventMode.OFFLINE)
                .venue("Main Auditorium, Block A")
                .startDateTime(now.plusDays(20).withHour(9).withMinute(0))
                .endDateTime(now.plusDays(21).withHour(9).withMinute(0))
                .registrationDeadline(now.plusDays(18))
                .capacity(200)
                .rules("Teams of 2-4. Original work only. Bring your own laptop.")
                .instructions("Check in at the registration desk with your ticket QR code.")
                .status(EventStatus.PUBLISHED)
                .teamEvent(true)
                .minTeamSize(2)
                .maxTeamSize(4)
                .featured(true)
                .club(coding)
                .createdBy(coordinator)
                .build();

        Event workshop = Event.builder()
                .title("Intro to Spring Boot Workshop")
                .description("A hands-on evening workshop covering REST APIs, JPA and security with Spring Boot 3.")
                .category("Workshop")
                .mode(EventMode.ONLINE)
                .onlineUrl("https://meet.campusconnect.local/spring-boot")
                .startDateTime(now.plusDays(7).withHour(18).withMinute(0))
                .endDateTime(now.plusDays(7).withHour(20).withMinute(0))
                .registrationDeadline(now.plusDays(6))
                .capacity(100)
                .status(EventStatus.PUBLISHED)
                .featured(false)
                .club(coding)
                .createdBy(member)
                .build();

        Event bootcamp = Event.builder()
                .title("AI/ML Bootcamp")
                .description("A weekend bootcamp on the fundamentals of machine learning with practical labs.")
                .category("Bootcamp")
                .mode(EventMode.HYBRID)
                .venue("Seminar Hall 2")
                .onlineUrl("https://meet.campusconnect.local/aiml")
                .startDateTime(now.plusDays(30).withHour(10).withMinute(0))
                .endDateTime(now.plusDays(31).withHour(17).withMinute(0))
                .registrationDeadline(now.plusDays(27))
                .capacity(80)
                .paidEvent(true)
                .fee(new BigDecimal("299.00"))
                .status(EventStatus.PUBLISHED)
                .featured(true)
                .club(coding)
                .createdBy(coordinator)
                .build();

        Event culturalFest = Event.builder()
                .title("Rhythm Nights 2026")
                .description("An evening of live music, dance performances and an open mic.")
                .category("Cultural")
                .mode(EventMode.OFFLINE)
                .venue("Open Air Theatre")
                .startDateTime(now.plusDays(12).withHour(17).withMinute(30))
                .endDateTime(now.plusDays(12).withHour(21).withMinute(0))
                .registrationDeadline(now.plusDays(10))
                .capacity(500)
                .status(EventStatus.PUBLISHED)
                .featured(true)
                .club(cultural)
                .createdBy(coordinator)
                .build();

        Event pastEvent = Event.builder()
                .title("CodeSprint 1.0")
                .description("Our introductory competitive-programming sprint. Thanks to everyone who took part!")
                .category("Competition")
                .mode(EventMode.OFFLINE)
                .venue("Lab 3, Block C")
                .startDateTime(now.minusDays(25).withHour(10).withMinute(0))
                .endDateTime(now.minusDays(25).withHour(14).withMinute(0))
                .registrationDeadline(now.minusDays(27))
                .capacity(60)
                .status(EventStatus.COMPLETED)
                .club(coding)
                .createdBy(coordinator)
                .build();

        eventRepository.saveAll(java.util.List.of(hackfest, workshop, bootcamp, culturalFest, pastEvent));

        log.info("""
                Seed complete.
                  Demo accounts (password for all: {}):
                    Admin       : admin@campusconnect.local
                    Coordinator : coordinator@campusconnect.local
                    Club member : member@campusconnect.local
                    Student     : student@campusconnect.local
                    Volunteer   : volunteer@campusconnect.local
                  Clubs: 3  |  Events: 5""", DEMO_PASSWORD);
    }

    private User createUser(String fullName, String email, Role role,
                            String studentId, String department, String phone) {
        return User.builder()
                .fullName(fullName)
                .email(email)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .role(role)
                .studentId(studentId)
                .department(department)
                .phone(phone)
                .enabled(true)
                .emailVerified(true)
                .build();
    }

        private void ensureDemoAdmin() {
                if (userRepository.findByEmail("admin@campusconnect.local").isEmpty()) {
                        User admin = userRepository.save(createUser("Platform Admin", "admin@campusconnect.local",
                                        Role.ADMIN, "CC0000001", "Administration", "+91-9000000000"));
                        notificationPreferenceRepository.save(NotificationPreference.defaultsFor(admin));
                        log.info("Added missing demo admin account.");
                }
        }

    private Club createClub(String name, String description, String category, User createdBy) {
        return Club.builder()
                .name(name)
                .description(description)
                .category(category)
                .contactEmail("contact@campusconnect.local")
                .active(true)
                .createdBy(createdBy)
                .build();
    }

    private ClubMember membership(Club club, User user, ClubRole clubRole, MembershipStatus status) {
        return ClubMember.builder()
                .club(club)
                .user(user)
                .clubRole(clubRole)
                .status(status)
                .build();
    }
}
