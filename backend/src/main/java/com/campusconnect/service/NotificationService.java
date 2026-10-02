package com.campusconnect.service;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.NotificationPreferenceRequest;
import com.campusconnect.dto.response.NotificationPreferenceResponse;
import com.campusconnect.dto.response.NotificationResponse;
import com.campusconnect.entity.enums.NotificationType;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface NotificationService {

    /**
     * Persist a notification for a user and push it over WebSocket, and — when the recipient's
     * preferences allow it — send a matching email. Never throws for a missing recipient or a
     * failed email/push: notifications are best-effort side effects and must not break the
     * primary business action.
     */
    void notifyUser(Long userId, NotificationType type, String title, String message, String link);

    PageResponse<NotificationResponse> myNotifications(Long userId, Pageable pageable);

    List<NotificationResponse> myUnread(Long userId);

    long unreadCount(Long userId);

    void markRead(Long userId, Long notificationId);

    void markAllRead(Long userId);

    /** Flip a notification back to unread. Owner-only; throws if it isn't the caller's. */
    void markUnread(Long userId, Long notificationId);

    /** Permanently delete one of the caller's notifications. Owner-only. */
    void delete(Long userId, Long notificationId);

    /** Permanently delete every notification owned by the caller (clear all). */
    void deleteAll(Long userId);

    /** The user's email notification preferences, creating all-on defaults on first access. */
    NotificationPreferenceResponse getPreferences(Long userId);

    /** Replace the user's email notification preferences with the supplied state. */
    NotificationPreferenceResponse updatePreferences(Long userId, NotificationPreferenceRequest request);

    /**
     * Turn off all email for the account owning the given unsubscribe token (one-click unsubscribe).
     * In-app notifications are unaffected. Throws {@code BadRequestException} for an unknown token.
     */
    void unsubscribe(String token);
}
