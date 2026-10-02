package com.campusconnect.service.impl;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.NotificationPreferenceRequest;
import com.campusconnect.dto.response.NotificationPreferenceResponse;
import com.campusconnect.dto.response.NotificationResponse;
import com.campusconnect.entity.Notification;
import com.campusconnect.entity.NotificationPreference;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.NotificationMapper;
import com.campusconnect.mapper.NotificationPreferenceMapper;
import com.campusconnect.repository.NotificationPreferenceRepository;
import com.campusconnect.repository.NotificationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.service.EmailService;
import com.campusconnect.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationPreferenceRepository preferenceRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final EmailService emailService;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Override
    @Transactional
    public void notifyUser(Long userId, NotificationType type, String title, String message, String link) {
        User recipient = userRepository.findById(userId).orElse(null);
        if (recipient == null) {
            log.warn("Skipping notification for unknown user id {}", userId);
            return;
        }
        Notification notification = Notification.builder()
                .recipient(recipient)
                .type(type)
                .title(title)
                .message(message)
                .link(link)
                .read(false)
                .build();
        notification = notificationRepository.save(notification);

        NotificationResponse payload = NotificationMapper.toResponse(notification);
        try {
            messagingTemplate.convertAndSend("/topic/notifications/" + userId, payload);
        } catch (RuntimeException ex) {
            // A messaging failure must never roll back or break the originating business action.
            log.warn("Failed to push notification over WebSocket for user {}: {}", userId, ex.getMessage());
        }

        // Email is opt-out per category. In-app delivery above always happens; this only
        // adds an email when the recipient's preferences allow it. Best-effort — a failure
        // here (or a missing preference row) must never break the primary business action.
        sendEmailIfAllowed(recipient, type, title, message, link);
    }

    private void sendEmailIfAllowed(User recipient, NotificationType type, String title, String message, String link) {
        try {
            NotificationPreference preference = preferenceRepository.findByUserId(recipient.getId()).orElse(null);
            if (preference == null || !preference.allowsEmail(type)) {
                return;
            }
            emailService.send(recipient.getEmail(), title,
                    buildEmailBody(message, link, preference.getUnsubscribeToken()));
        } catch (RuntimeException ex) {
            log.warn("Failed to send notification email to user {}: {}", recipient.getId(), ex.getMessage());
        }
    }

    private String buildEmailBody(String message, String link, String unsubscribeToken) {
        StringBuilder body = new StringBuilder();
        if (message != null && !message.isBlank()) {
            body.append(message.strip()).append("\n\n");
        }
        if (link != null && !link.isBlank()) {
            body.append("View it here: ").append(absoluteUrl(link)).append("\n\n");
        }
        body.append("—\n")
                .append("You're receiving this because you have CampusConnect email notifications enabled.\n")
                .append("Manage your preferences or unsubscribe: ")
                .append(unsubscribeUrl(unsubscribeToken));
        return body.toString();
    }

    private String unsubscribeUrl(String token) {
        return baseUrl() + "/unsubscribe?token=" + token;
    }

    private String absoluteUrl(String link) {
        if (link.startsWith("http://") || link.startsWith("https://")) {
            return link;
        }
        return link.startsWith("/") ? baseUrl() + link : baseUrl() + "/" + link;
    }

    private String baseUrl() {
        if (frontendBaseUrl == null || frontendBaseUrl.isBlank()) {
            return "";
        }
        return frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> myNotifications(Long userId, Pageable pageable) {
        Page<Notification> page = notificationRepository.findByRecipientIdOrderByCreatedAtDesc(userId, pageable);
        return PageResponse.from(page, NotificationMapper::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationResponse> myUnread(Long userId) {
        return notificationRepository.findByRecipientIdAndReadFalseOrderByCreatedAtDesc(userId).stream()
                .map(NotificationMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return notificationRepository.countByRecipientIdAndReadFalse(userId);
    }

    @Override
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        Notification notification = requireOwned(userId, notificationId);
        if (!notification.isRead()) {
            notification.setRead(true);
            notificationRepository.save(notification);
        }
    }

    @Override
    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllRead(userId);
    }

    @Override
    @Transactional
    public void markUnread(Long userId, Long notificationId) {
        Notification notification = requireOwned(userId, notificationId);
        if (notification.isRead()) {
            notification.setRead(false);
            notificationRepository.save(notification);
        }
    }

    @Override
    @Transactional
    public void delete(Long userId, Long notificationId) {
        Notification notification = requireOwned(userId, notificationId);
        notificationRepository.delete(notification);
    }

    @Override
    @Transactional
    public void deleteAll(Long userId) {
        notificationRepository.deleteByRecipientId(userId);
    }

    /**
     * Load a notification and assert it belongs to {@code userId}. Centralises the
     * ownership check so every mutating operation (read/unread/delete) enforces it
     * identically — a user must never be able to touch another user's notifications.
     */
    private Notification requireOwned(Long userId, Long notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification", "id", notificationId));
        if (notification.getRecipient() == null || !notification.getRecipient().getId().equals(userId)) {
            throw new ForbiddenException("You can only manage your own notifications.");
        }
        return notification;
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse getPreferences(Long userId) {
        return NotificationPreferenceMapper.toResponse(resolvePreference(userId));
    }

    @Override
    @Transactional
    public NotificationPreferenceResponse updatePreferences(Long userId, NotificationPreferenceRequest request) {
        NotificationPreference preference = resolvePreference(userId);
        preference.setEmailEnabled(request.emailEnabled());
        preference.setEmailOnEvents(request.emailOnEvents());
        preference.setEmailOnAnnouncements(request.emailOnAnnouncements());
        preference.setEmailOnCertificates(request.emailOnCertificates());
        preference.setEmailOnPayments(request.emailOnPayments());
        preference.setEmailOnGeneral(request.emailOnGeneral());
        return NotificationPreferenceMapper.toResponse(preferenceRepository.save(preference));
    }

    @Override
    @Transactional
    public void unsubscribe(String token) {
        if (token == null || token.isBlank()) {
            throw new BadRequestException("Missing unsubscribe token.");
        }
        NotificationPreference preference = preferenceRepository.findByUnsubscribeToken(token.trim())
                .orElseThrow(() -> new BadRequestException("This unsubscribe link is invalid or has expired."));
        if (preference.isEmailEnabled()) {
            preference.setEmailEnabled(false);
            preferenceRepository.save(preference);
        }
    }

    /**
     * The user's preferences, creating an all-on default row on first access. Real users get a
     * row at registration (and seeded users are pre-populated), so this create path is only a
     * safety net for accounts that predate the feature.
     */
    private NotificationPreference resolvePreference(Long userId) {
        return preferenceRepository.findByUserId(userId).orElseGet(() -> {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
            return preferenceRepository.save(NotificationPreference.defaultsFor(user));
        });
    }
}
