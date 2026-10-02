package com.campusconnect.service;

import com.campusconnect.dto.request.FeedbackRequest;
import com.campusconnect.dto.response.FeedbackResponse;
import com.campusconnect.dto.response.FeedbackSummary;

import java.util.List;

public interface FeedbackService {

    /** Create or update the current user's feedback for an event. */
    FeedbackResponse submit(Long userId, FeedbackRequest request);

    FeedbackResponse myFeedbackForEvent(Long userId, Long eventId);

    /** Individual responses — visible to the event's club coordinator only. */
    List<FeedbackResponse> eventFeedback(Long actingUserId, Long eventId);

    /** Remove a feedback entry — moderation action for the event's club coordinator. */
    void delete(Long actingUserId, Long feedbackId);

    /** Public aggregate summary (average + distribution). */
    FeedbackSummary eventSummary(Long eventId);
}
