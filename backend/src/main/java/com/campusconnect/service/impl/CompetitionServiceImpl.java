package com.campusconnect.service.impl;

import com.campusconnect.dto.request.AddJudgeRequest;
import com.campusconnect.dto.request.CompetitionRequest;
import com.campusconnect.dto.request.CompetitionRoundRequest;
import com.campusconnect.dto.request.ScoreRequest;
import com.campusconnect.dto.response.CompetitionResponse;
import com.campusconnect.dto.response.CompetitionRoundResponse;
import com.campusconnect.dto.response.JudgeResponse;
import com.campusconnect.dto.response.LeaderboardEntry;
import com.campusconnect.dto.response.ScoreResponse;
import com.campusconnect.entity.Competition;
import com.campusconnect.entity.CompetitionRound;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Judge;
import com.campusconnect.entity.Score;
import com.campusconnect.entity.Team;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.CompetitionStatus;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.CompetitionMapper;
import com.campusconnect.repository.CompetitionRepository;
import com.campusconnect.repository.CompetitionRoundRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.JudgeRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.ScoreRepository;
import com.campusconnect.repository.TeamRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.CompetitionService;
import com.campusconnect.service.EntityPurgeService;
import com.campusconnect.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompetitionServiceImpl implements CompetitionService {

    private final CompetitionRepository competitionRepository;
    private final CompetitionRoundRepository roundRepository;
    private final JudgeRepository judgeRepository;
    private final ScoreRepository scoreRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final RegistrationRepository registrationRepository;
    private final ClubAccess clubAccess;
    private final SimpMessagingTemplate messagingTemplate;
    private final NotificationService notificationService;
    private final EntityPurgeService entityPurgeService;

    @Override
    @Transactional
    public CompetitionResponse create(Long actingUserId, CompetitionRequest request) {
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));
        clubAccess.requireCoordinator(event.getClub().getId(), actingUserId);

        Competition competition = Competition.builder()
                .event(event)
                .title(request.title().trim())
                .description(request.description())
                .teamBased(request.teamBased())
                .status(CompetitionStatus.DRAFT)
                .build();
        competition = competitionRepository.save(competition);
        return CompetitionMapper.toResponse(competition, 0, 0);
    }

    @Override
    @Transactional
    public void deleteCompetition(Long actingUserId, Long competitionId) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);
        // Remove the scoring subtree (scores -> rounds -> judges) and the
        // competition in dependency order; there is no ON DELETE CASCADE.
        entityPurgeService.purgeCompetition(competitionId);
    }

    @Override
    @Transactional
    public CompetitionResponse updateStatus(Long actingUserId, Long competitionId, CompetitionStatus status) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);
        competition.setStatus(status);
        competition = competitionRepository.save(competition);
        return toResponseWithCounts(competition);
    }

    @Override
    @Transactional(readOnly = true)
    public CompetitionResponse getById(Long competitionId) {
        return toResponseWithCounts(getCompetition(competitionId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompetitionResponse> listByEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        return competitionRepository.findByEventId(eventId).stream()
                .map(this::toResponseWithCounts)
                .toList();
    }

    @Override
    @Transactional
    public CompetitionRoundResponse addRound(Long actingUserId, Long competitionId, CompetitionRoundRequest request) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);

        CompetitionRound round = CompetitionRound.builder()
                .competition(competition)
                .name(request.name().trim())
                .roundNumber(request.roundNumber())
                .description(request.description())
                .maxScore(request.maxScore())
                .scheduledAt(request.scheduledAt())
                .build();
        round = roundRepository.save(round);
        return CompetitionMapper.toRoundResponse(round);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CompetitionRoundResponse> listRounds(Long competitionId) {
        if (!competitionRepository.existsById(competitionId)) {
            throw new ResourceNotFoundException("Competition", "id", competitionId);
        }
        return roundRepository.findByCompetitionIdOrderByRoundNumberAsc(competitionId).stream()
                .map(CompetitionMapper::toRoundResponse)
                .toList();
    }

    @Override
    @Transactional
    public void deleteRound(Long actingUserId, Long roundId) {
        CompetitionRound round = roundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("Round", "id", roundId));
        requireEventCoordinator(round.getCompetition(), actingUserId);
        if (!scoreRepository.findByRoundId(roundId).isEmpty()) {
            throw new BadRequestException("This round already has scores and cannot be deleted.");
        }
        roundRepository.delete(round);
    }

    @Override
    @Transactional
    public JudgeResponse addJudge(Long actingUserId, Long competitionId, AddJudgeRequest request) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);

        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.email()));
        if (judgeRepository.existsByCompetitionIdAndUserId(competitionId, user.getId())) {
            throw new ConflictException("That user is already a judge for this competition.");
        }
        Judge judge = judgeRepository.save(Judge.builder().competition(competition).user(user).build());

        notificationService.notifyUser(user.getId(), NotificationType.GENERAL,
                "You have been added as a judge",
                "You are now a judge for the competition \"" + competition.getTitle() + "\".",
                "/competitions/" + competitionId);
        return CompetitionMapper.toJudgeResponse(judge);
    }

    @Override
    @Transactional(readOnly = true)
    public List<JudgeResponse> listJudges(Long actingUserId, Long competitionId) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);
        return judgeRepository.findByCompetitionId(competitionId).stream()
                .map(CompetitionMapper::toJudgeResponse)
                .toList();
    }

    @Override
    @Transactional
    public void removeJudge(Long actingUserId, Long competitionId, Long judgeId) {
        Competition competition = getCompetition(competitionId);
        requireEventCoordinator(competition, actingUserId);
        Judge judge = judgeRepository.findById(judgeId)
                .orElseThrow(() -> new ResourceNotFoundException("Judge", "id", judgeId));
        if (judge.getCompetition() == null || !judge.getCompetition().getId().equals(competitionId)) {
            throw new BadRequestException("That judge does not belong to this competition.");
        }
        // Score.judge is a NOT NULL FK with no ON DELETE CASCADE, so deleting a
        // judge who has already scored would throw a data-integrity violation
        // (surfaced as a 409). Removing a judge invalidates their scoring, so
        // clear their scores first, then remove the judge.
        scoreRepository.deleteByJudgeId(judgeId);
        judgeRepository.delete(judge);
    }

    @Override
    @Transactional
    public ScoreResponse submitScore(Long actingJudgeUserId, ScoreRequest request) {
        CompetitionRound round = roundRepository.findById(request.roundId())
                .orElseThrow(() -> new ResourceNotFoundException("Round", "id", request.roundId()));
        Competition competition = round.getCompetition();

        if (competition.getStatus() != CompetitionStatus.ONGOING) {
            throw new BadRequestException("Scoring is only open while the competition is ongoing.");
        }
        Judge judge = judgeRepository.findByCompetitionIdAndUserId(competition.getId(), actingJudgeUserId)
                .orElseThrow(() -> new ForbiddenException("Only an assigned judge can submit scores."));
        if (request.points() > round.getMaxScore()) {
            throw new BadRequestException("Points cannot exceed the round maximum of " + round.getMaxScore() + ".");
        }

        Long eventId = competition.getEvent() != null ? competition.getEvent().getId() : null;
        Score score;
        if (competition.isTeamBased()) {
            if (request.teamId() == null) {
                throw new BadRequestException("This is a team competition. A teamId is required.");
            }
            if (request.participantId() != null) {
                throw new BadRequestException("Provide only teamId for a team competition.");
            }
            Team team = teamRepository.findById(request.teamId())
                    .orElseThrow(() -> new ResourceNotFoundException("Team", "id", request.teamId()));
            if (team.getEvent() == null || !team.getEvent().getId().equals(eventId)) {
                throw new BadRequestException("That team is not part of this competition's event.");
            }
            score = scoreRepository.findByRoundIdAndJudgeIdAndTeamId(round.getId(), judge.getId(), team.getId())
                    .orElseGet(Score::new);
            score.setTeam(team);
            score.setParticipant(null);
        } else {
            if (request.participantId() == null) {
                throw new BadRequestException("This is an individual competition. A participantId is required.");
            }
            if (request.teamId() != null) {
                throw new BadRequestException("Provide only participantId for an individual competition.");
            }
            User participant = userRepository.findById(request.participantId())
                    .orElseThrow(() -> new ResourceNotFoundException("User", "id", request.participantId()));
            if (eventId != null && !registrationRepository.existsByEventIdAndUserId(eventId, participant.getId())) {
                throw new BadRequestException("That participant is not registered for this event.");
            }
            score = scoreRepository.findByRoundIdAndJudgeIdAndParticipantId(round.getId(), judge.getId(), participant.getId())
                    .orElseGet(Score::new);
            score.setParticipant(participant);
            score.setTeam(null);
        }
        score.setRound(round);
        score.setJudge(judge);
        score.setPoints(request.points());
        score.setRemarks(request.remarks());
        score = scoreRepository.save(score);

        publishLeaderboard(competition);
        return CompetitionMapper.toScoreResponse(score);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScoreResponse> listScoresByRound(Long actingUserId, Long roundId) {
        CompetitionRound round = roundRepository.findById(roundId)
                .orElseThrow(() -> new ResourceNotFoundException("Round", "id", roundId));
        requireCoordinatorOrJudge(round.getCompetition(), actingUserId);
        return scoreRepository.findByRoundId(roundId).stream()
                .map(CompetitionMapper::toScoreResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<LeaderboardEntry> leaderboard(Long competitionId) {
        return computeLeaderboard(getCompetition(competitionId));
    }

    // ---- helpers ----

    private CompetitionResponse toResponseWithCounts(Competition competition) {
        int roundCount = roundRepository.findByCompetitionIdOrderByRoundNumberAsc(competition.getId()).size();
        int judgeCount = judgeRepository.findByCompetitionId(competition.getId()).size();
        return CompetitionMapper.toResponse(competition, roundCount, judgeCount);
    }

    private List<LeaderboardEntry> computeLeaderboard(Competition competition) {
        boolean teamBased = competition.isTeamBased();
        List<Score> scores = scoreRepository.findByCompetitionId(competition.getId());

        Map<Long, Double> totals = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        for (Score score : scores) {
            Long key;
            String name;
            if (teamBased) {
                if (score.getTeam() == null) {
                    continue;
                }
                key = score.getTeam().getId();
                name = score.getTeam().getName();
            } else {
                if (score.getParticipant() == null) {
                    continue;
                }
                key = score.getParticipant().getId();
                name = score.getParticipant().getFullName();
            }
            double points = score.getPoints() == null ? 0.0 : score.getPoints();
            totals.merge(key, points, Double::sum);
            names.putIfAbsent(key, name);
        }

        List<Map.Entry<Long, Double>> sorted = new ArrayList<>(totals.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));

        List<LeaderboardEntry> leaderboard = new ArrayList<>(sorted.size());
        int position = 0;
        int rank = 0;
        Double previous = null;
        for (Map.Entry<Long, Double> entry : sorted) {
            position++;
            if (previous == null || entry.getValue() < previous) {
                rank = position;
                previous = entry.getValue();
            }
            double rounded = Math.round(entry.getValue() * 100.0) / 100.0;
            leaderboard.add(new LeaderboardEntry(
                    rank,
                    teamBased ? "TEAM" : "INDIVIDUAL",
                    entry.getKey(),
                    names.get(entry.getKey()),
                    rounded));
        }
        return leaderboard;
    }

    private void publishLeaderboard(Competition competition) {
        List<LeaderboardEntry> leaderboard = computeLeaderboard(competition);
        try {
            messagingTemplate.convertAndSend(
                    "/topic/competitions/" + competition.getId() + "/leaderboard", leaderboard);
        } catch (RuntimeException ex) {
            log.warn("Failed to broadcast leaderboard for competition {}: {}",
                    competition.getId(), ex.getMessage());
        }
    }

    private void requireEventCoordinator(Competition competition, Long userId) {
        Event event = competition.getEvent();
        if (event == null || event.getClub() == null) {
            throw new BadRequestException("This competition is not linked to a club event.");
        }
        clubAccess.requireCoordinator(event.getClub().getId(), userId);
    }

    private void requireCoordinatorOrJudge(Competition competition, Long userId) {
        Event event = competition.getEvent();
        boolean coordinator = event != null && event.getClub() != null
                && clubAccess.isCoordinator(event.getClub().getId(), userId);
        boolean judge = judgeRepository.existsByCompetitionIdAndUserId(competition.getId(), userId);
        if (!coordinator && !judge) {
            throw new ForbiddenException("Only a coordinator or an assigned judge can view scores.");
        }
    }

    private Competition getCompetition(Long competitionId) {
        return competitionRepository.findById(competitionId)
                .orElseThrow(() -> new ResourceNotFoundException("Competition", "id", competitionId));
    }
}
