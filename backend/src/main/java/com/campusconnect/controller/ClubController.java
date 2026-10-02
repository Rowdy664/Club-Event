package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.common.PageRequests;
import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.ClubRequest;
import com.campusconnect.dto.response.ClubMemberResponse;
import com.campusconnect.dto.response.ClubResponse;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.ClubService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Clubs", description = "Club directory, membership and management")
@RestController
@RequestMapping("/api/clubs")
@RequiredArgsConstructor
public class ClubController {

    private final ClubService clubService;

    @Operation(summary = "List/search clubs (public)")
    @GetMapping
    public ApiResponse<PageResponse<ClubResponse>> search(
            @AuthenticationPrincipal UserPrincipal principal,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        Long viewerId = principal != null ? principal.getId() : null;
        return ApiResponse.success(clubService.search(q, category, active, viewerId,
                PageRequests.of(page, size, Sort.by("name").ascending())));
    }

    @Operation(summary = "Get a club by id (public)")
    @GetMapping("/{id}")
    public ApiResponse<ClubResponse> getById(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id) {
        Long viewerId = principal != null ? principal.getId() : null;
        return ApiResponse.success(clubService.getById(id, viewerId));
    }

    @Operation(summary = "Create a club (coordinators only)")
    @PreAuthorize("hasRole('CLUB_COORDINATOR')")
    @PostMapping
    public ResponseEntity<ApiResponse<ClubResponse>> create(@AuthenticationPrincipal UserPrincipal principal,
                                                            @Valid @RequestBody ClubRequest request) {
        ClubResponse created = clubService.create(principal.getId(), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success("Club created.", created));
    }

    @Operation(summary = "Update a club (club coordinator only)")
    @PutMapping("/{id}")
    public ApiResponse<ClubResponse> update(@AuthenticationPrincipal UserPrincipal principal,
                                            @PathVariable Long id,
                                            @Valid @RequestBody ClubRequest request) {
        return ApiResponse.success("Club updated.", clubService.update(principal.getId(), id, request));
    }

    @Operation(summary = "Deactivate a club (club coordinator only)")
    @DeleteMapping("/{id}")
    public ApiResponse<Void> deactivate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        clubService.deactivate(principal.getId(), id);
        return ApiResponse.message("Club deactivated.");
    }

    @Operation(summary = "Reactivate a previously-deactivated club (club coordinator only)")
    @PostMapping("/{id}/reactivate")
    public ApiResponse<Void> reactivate(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        clubService.reactivate(principal.getId(), id);
        return ApiResponse.message("Club reactivated.");
    }

    @Operation(summary = "List members of a club (active members only)")
    @GetMapping("/{id}/members")
    public ApiResponse<List<ClubMemberResponse>> members(@AuthenticationPrincipal UserPrincipal principal,
                                                         @PathVariable Long id) {
        return ApiResponse.success(clubService.listMembers(principal.getId(), id));
    }

    @Operation(summary = "Request to join a club")
    @PostMapping("/{id}/join")
    public ApiResponse<ClubMemberResponse> join(@AuthenticationPrincipal UserPrincipal principal,
                                                @PathVariable Long id) {
        return ApiResponse.success("Join request submitted.", clubService.join(principal.getId(), id));
    }

    @Operation(summary = "Approve a pending membership (coordinator only)")
    @PostMapping("/{id}/members/{membershipId}/approve")
    public ApiResponse<ClubMemberResponse> approve(@AuthenticationPrincipal UserPrincipal principal,
                                                   @PathVariable Long id,
                                                   @PathVariable Long membershipId) {
        return ApiResponse.success("Member approved.",
                clubService.approveMember(principal.getId(), id, membershipId));
    }

    @Operation(summary = "Remove/reject a membership (coordinator only)")
    @DeleteMapping("/{id}/members/{membershipId}")
    public ApiResponse<Void> removeMember(@AuthenticationPrincipal UserPrincipal principal,
                                          @PathVariable Long id,
                                          @PathVariable Long membershipId) {
        clubService.removeMember(principal.getId(), id, membershipId);
        return ApiResponse.message("Membership removed.");
    }

    @Operation(summary = "Leave a club")
    @PostMapping("/{id}/leave")
    public ApiResponse<Void> leave(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        clubService.leave(principal.getId(), id);
        return ApiResponse.message("You have left the club.");
    }

    @Operation(summary = "List the current user's club memberships")
    @GetMapping("/me/memberships")
    public ApiResponse<List<ClubMemberResponse>> myMemberships(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(clubService.myMemberships(principal.getId()));
    }

    @Operation(summary = "Follow a club to get notified about its new events")
    @PostMapping("/{id}/follow")
    public ApiResponse<Void> follow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        clubService.follow(principal.getId(), id);
        return ApiResponse.message("You are now following this club.");
    }

    @Operation(summary = "Unfollow a club")
    @DeleteMapping("/{id}/follow")
    public ApiResponse<Void> unfollow(@AuthenticationPrincipal UserPrincipal principal, @PathVariable Long id) {
        clubService.unfollow(principal.getId(), id);
        return ApiResponse.message("You have unfollowed this club.");
    }

    @Operation(summary = "List clubs the current user follows")
    @GetMapping("/me/following")
    public ApiResponse<List<ClubResponse>> myFollowing(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(clubService.myFollowedClubs(principal.getId()));
    }
}
