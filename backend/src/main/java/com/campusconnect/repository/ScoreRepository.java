package com.campusconnect.repository;

import com.campusconnect.entity.Score;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ScoreRepository extends JpaRepository<Score, Long> {

    List<Score> findByRoundId(Long roundId);

    @Query("select s from Score s where s.round.competition.id = :competitionId")
    List<Score> findByCompetitionId(@Param("competitionId") Long competitionId);

    Optional<Score> findByRoundIdAndJudgeIdAndParticipantId(Long roundId, Long judgeId, Long participantId);

    Optional<Score> findByRoundIdAndJudgeIdAndTeamId(Long roundId, Long judgeId, Long teamId);

    /** Bulk-remove every score cast by a judge (used when a judge is removed). */
    @Modifying
    @Query("delete from Score s where s.judge.id = :judgeId")
    void deleteByJudgeId(@Param("judgeId") Long judgeId);
}
