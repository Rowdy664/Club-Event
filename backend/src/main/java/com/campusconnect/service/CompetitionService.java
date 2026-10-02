package com.campusconnect.service;

import com.campusconnect.dto.request.AddJudgeRequest;
import com.campusconnect.dto.request.CompetitionRequest;
import com.campusconnect.dto.request.CompetitionRoundRequest;
import com.campusconnect.dto.request.ScoreRequest;
import com.campusconnect.dto.response.CompetitionResponse;
import com.campusconnect.dto.response.CompetitionRoundResponse;
import com.campusconnect.dto.response.JudgeResponse;
import com.campusconnect.dto.response.LeaderboardEntry;
import com.campusconnect.dto.response.ScoreResponse;
import com.campusconnect.entity.enums.CompetitionStatus;

import java.util.List;

public interface CompetitionService {

    CompetitionResponse create(Long actingUserId, CompetitionRequest request);

    /** Delete a competition together with its rounds, judges and scores (event's club coordinator only). */
    void deleteCompetition(Long actingUserId, Long competitionId);

    CompetitionResponse updateStatus(Long actingUserId, Long competitionId, CompetitionStatus status);

    CompetitionResponse getById(Long competitionId);

    List<CompetitionResponse> listByEvent(Long eventId);

    CompetitionRoundResponse addRound(Long actingUserId, Long competitionId, CompetitionRoundRequest request);

    List<CompetitionRoundResponse> listRounds(Long competitionId);

    void deleteRound(Long actingUserId, Long roundId);

    JudgeResponse addJudge(Long actingUserId, Long competitionId, AddJudgeRequest request);

    List<JudgeResponse> listJudges(Long actingUserId, Long competitionId);

    void removeJudge(Long actingUserId, Long competitionId, Long judgeId);

    /** Submit (or update) a score. The acting user must be a judge of the competition. */
    ScoreResponse submitScore(Long actingJudgeUserId, ScoreRequest request);

    List<ScoreResponse> listScoresByRound(Long actingUserId, Long roundId);

    List<LeaderboardEntry> leaderboard(Long competitionId);
}
