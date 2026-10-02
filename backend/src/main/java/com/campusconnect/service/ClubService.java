package com.campusconnect.service;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.ClubRequest;
import com.campusconnect.dto.response.ClubMemberResponse;
import com.campusconnect.dto.response.ClubResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;

public interface ClubService {

    ClubResponse create(Long userId, ClubRequest request);

    ClubResponse update(Long userId, Long clubId, ClubRequest request);

    void deactivate(Long userId, Long clubId);

    /** Bring a previously-deactivated club back online. Coordinator-scoped, mirrors {@link #deactivate}. */
    void reactivate(Long userId, Long clubId);

    ClubResponse getById(Long clubId, Long viewerId);

    PageResponse<ClubResponse> search(String q, String category, Boolean active, Long viewerId, Pageable pageable);

    List<ClubMemberResponse> listMembers(Long userId, Long clubId);

    ClubMemberResponse join(Long userId, Long clubId);

    ClubMemberResponse approveMember(Long actingUserId, Long clubId, Long membershipId);

    void removeMember(Long actingUserId, Long clubId, Long membershipId);

    void leave(Long userId, Long clubId);

    List<ClubMemberResponse> myMemberships(Long userId);

    void follow(Long userId, Long clubId);

    void unfollow(Long userId, Long clubId);

    List<ClubResponse> myFollowedClubs(Long userId);
}
