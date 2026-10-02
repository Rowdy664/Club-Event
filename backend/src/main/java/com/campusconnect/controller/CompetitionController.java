package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.AddJudgeRequest;
import com.campusconnect.dto.request.CompetitionRequest;
import com.campusconnect.dto.request.CompetitionRoundRequest;
import com.campusconnect.dto.request.ScoreRequest;
import com.campusconnect.dto.request.UpdateCompetitionStatusRequest;
import com.campusconnect.dto.response.CompetitionResponse;
import com.campusconnect.dto.response.CompetitionRoundResponse;
import com.campusconnect.dto.response.JudgeResponse;
import com.campusconnect.dto.response.LeaderboardEntry;
import com.campusconnect.dto.response.ScoreResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.CompetitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/competitions")
@RequiredArgsConstructor
@Tag(name = "Competitions", description = "Competitions, rounds, judging, scoring and live leaderboards")
public class CompetitionController {

    private final CompetitionService competitionService;

    // ---- public reads ----

    @GetMapping("/event/{eventId}")
    @Operation(summary = "List competitions for an event")
    public ApiResponse<List<CompetitionResponse>> listByEvent(@PathVariable Long eventId) {
        return ApiResponse.success(competitionService.listByEvent(eventId));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a competition by id")
    public ApiResponse<CompetitionResponse> getById(@PathVariable Long id) {
        return ApiResponse.success(competitionService.getById(id));
    }

    @GetMapping("/{id}/rounds")
    @Operation(summary = "List rounds of a competition")
    public ApiResponse<List<CompetitionRoundResponse>> listRounds(@PathVariable Long id) {
        return ApiResponse.success(competitionService.listRounds(id));
    }

    @GetMapping("/{id}/leaderboard")
    @Operation(summary = "Get the current leaderboard for a competition")
    public ApiResponse<List<LeaderboardEntry>> leaderboard(@PathVariable Long id) {
        return ApiResponse.success(competitionService.leaderboard(id));
    }

    // ---- coordinator management ----

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Create a competition (club coordinator only)")
    public ApiResponse<CompetitionResponse> create(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody CompetitionRequest request) {
        return ApiResponse.success("Competition created",
                competitionService.create(principal.getId(), request));
    }

    @DeleteMapping("/{id}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a competition and all its rounds, judges and scores (club coordinator only)")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable Long id) {
        competitionService.deleteCompetition(principal.getId(), id);
        return ApiResponse.message("Competition deleted");
    }

    @PatchMapping("/{id}/status")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Update a competition's status (club coordinator only)")
    public ApiResponse<CompetitionResponse> updateStatus(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long id,
                                                         @Valid @RequestBody UpdateCompetitionStatusRequest request) {
        return ApiResponse.success("Status updated",
                competitionService.updateStatus(principal.getId(), id, request.status()));
    }

    @PostMapping("/{id}/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a round to a competition (club coordinator only)")
    public ApiResponse<CompetitionRoundResponse> addRound(@AuthenticationPrincipal UserPrincipal principal,
                                                          @PathVariable Long id,
                                                          @Valid @RequestBody CompetitionRoundRequest request) {
        return ApiResponse.success("Round added",
                competitionService.addRound(principal.getId(), id, request));
    }

    @DeleteMapping("/rounds/{roundId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Delete a round (club coordinator only; blocked once scored)")
    public ApiResponse<Void> deleteRound(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable Long roundId) {
        competitionService.deleteRound(principal.getId(), roundId);
        return ApiResponse.message("Round deleted");
    }

    @PostMapping("/{id}/judges")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Add a judge by email (club coordinator only)")
    public ApiResponse<JudgeResponse> addJudge(@AuthenticationPrincipal UserPrincipal principal,
                                               @PathVariable Long id,
                                               @Valid @RequestBody AddJudgeRequest request) {
        return ApiResponse.success("Judge added",
                competitionService.addJudge(principal.getId(), id, request));
    }

    @GetMapping("/{id}/judges")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List judges of a competition (club coordinator only)")
    public ApiResponse<List<JudgeResponse>> listJudges(@AuthenticationPrincipal UserPrincipal principal,
                                                       @PathVariable Long id) {
        return ApiResponse.success(competitionService.listJudges(principal.getId(), id));
    }

    @DeleteMapping("/{id}/judges/{judgeId}")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Remove a judge (club coordinator only)")
    public ApiResponse<Void> removeJudge(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable Long id,
                                         @PathVariable Long judgeId) {
        competitionService.removeJudge(principal.getId(), id, judgeId);
        return ApiResponse.message("Judge removed");
    }

    // ---- judging ----

    @PostMapping("/scores")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Submit or update a score (assigned judge only)")
    public ApiResponse<ScoreResponse> submitScore(@AuthenticationPrincipal UserPrincipal principal,
                                                  @Valid @RequestBody ScoreRequest request) {
        return ApiResponse.success("Score submitted",
                competitionService.submitScore(principal.getId(), request));
    }

    @GetMapping("/rounds/{roundId}/scores")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "List scores for a round (club coordinator or assigned judge)")
    public ApiResponse<List<ScoreResponse>> listScoresByRound(@AuthenticationPrincipal UserPrincipal principal,
                                                              @PathVariable Long roundId) {
        return ApiResponse.success(competitionService.listScoresByRound(principal.getId(), roundId));
    }
}
