package com.campusconnect.service.impl;

import com.campusconnect.dto.request.FeedbackRequest;
import com.campusconnect.dto.response.FeedbackResponse;
import com.campusconnect.dto.response.FeedbackSummary;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Feedback;
import com.campusconnect.entity.User;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.FeedbackMapper;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.FeedbackRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.FeedbackService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

@Service
@RequiredArgsConstructor
public class FeedbackServiceImpl implements FeedbackService {

    private final FeedbackRepository feedbackRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;
    private final ClubAccess clubAccess;

    @Override
    @Transactional
    public FeedbackResponse submit(Long userId, FeedbackRequest request) {
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));

        if (!registrationRepository.existsByEventIdAndUserId(event.getId(), userId)) {
            throw new BadRequestException("You can only leave feedback for events you registered for.");
        }

        Feedback feedback = feedbackRepository.findByEventIdAndUserId(event.getId(), userId)
                .orElseGet(() -> {
                    User user = userRepository.findById(userId)
                            .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
                    return Feedback.builder().event(event).user(user).build();
                });
        feedback.setRating(request.rating());
        feedback.setComment(request.comment());
        feedback.setSuggestion(request.suggestion());
        feedback = feedbackRepository.save(feedback);
        return FeedbackMapper.toResponse(feedback);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackResponse myFeedbackForEvent(Long userId, Long eventId) {
        return feedbackRepository.findByEventIdAndUserId(eventId, userId)
                .map(FeedbackMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", "eventId", eventId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FeedbackResponse> eventFeedback(Long actingUserId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        return feedbackRepository.findByEventId(eventId).stream()
                .map(FeedbackMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public void delete(Long actingUserId, Long feedbackId) {
        Feedback feedback = feedbackRepository.findById(feedbackId)
                .orElseThrow(() -> new ResourceNotFoundException("Feedback", "id", feedbackId));
        Event event = feedback.getEvent();
        if (event == null || event.getClub() == null) {
            throw new BadRequestException("This feedback is not linked to a managed event.");
        }
        // Same gate as viewing the individual feedback list: the club's coordinator (or a platform admin).
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        feedbackRepository.delete(feedback);
    }

    @Override
    @Transactional(readOnly = true)
    public FeedbackSummary eventSummary(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        List<Feedback> all = feedbackRepository.findByEventId(eventId);
        Map<Integer, Long> distribution = new TreeMap<>();
        for (int star = 1; star <= 5; star++) {
            distribution.put(star, 0L);
        }
        long sum = 0;
        for (Feedback f : all) {
            int rating = f.getRating() == null ? 0 : f.getRating();
            if (rating >= 1 && rating <= 5) {
                distribution.merge(rating, 1L, Long::sum);
                sum += rating;
            }
        }
        long total = all.size();
        double average = total == 0 ? 0.0 : Math.round(((double) sum / total) * 100.0) / 100.0;
        return new FeedbackSummary(eventId, average, total, distribution);
    }
}
