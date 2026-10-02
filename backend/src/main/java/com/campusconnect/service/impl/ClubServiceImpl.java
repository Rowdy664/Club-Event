package com.campusconnect.service.impl;

import com.campusconnect.common.PageResponse;
import com.campusconnect.dto.request.ClubRequest;
import com.campusconnect.dto.response.ClubMemberResponse;
import com.campusconnect.dto.response.ClubResponse;
import com.campusconnect.entity.Club;
import com.campusconnect.entity.ClubFollow;
import com.campusconnect.entity.ClubMember;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.ClubRole;
import com.campusconnect.entity.enums.MembershipStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.ClubMapper;
import com.campusconnect.repository.ClubFollowRepository;
import com.campusconnect.repository.ClubMemberRepository;
import com.campusconnect.repository.ClubRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.repository.spec.ClubSpecifications;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.ClubService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ClubServiceImpl implements ClubService {

    private final ClubRepository clubRepository;
    private final ClubMemberRepository clubMemberRepository;
    private final ClubFollowRepository clubFollowRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final ClubAccess clubAccess;

    @Override
    @Transactional
    public ClubResponse create(Long userId, ClubRequest request) {
        if (clubRepository.existsByName(request.name().trim())) {
            throw new ConflictException("A club with this name already exists.");
        }
        User creator = getUser(userId);
        Club club = Club.builder()
                .name(request.name().trim())
                .description(request.description())
                .category(request.category())
                .logoUrl(request.logoUrl())
                .coverImageUrl(request.coverImageUrl())
                .contactEmail(request.contactEmail())
                .contactPhone(request.contactPhone())
                .active(true)
                .createdBy(creator)
                .build();
        club = clubRepository.save(club);

        // The creator becomes the founding coordinator of the club.
        clubMemberRepository.save(ClubMember.builder()
                .club(club)
                .user(creator)
                .clubRole(ClubRole.COORDINATOR)
                .status(MembershipStatus.ACTIVE)
                .build());

        return ClubMapper.toResponse(club, 1, 0);
    }

    @Override
    @Transactional
    public ClubResponse update(Long userId, Long clubId, ClubRequest request) {
        Club club = getClub(clubId);
        clubAccess.requireCoordinator(clubId, userId);

        String newName = request.name().trim();
        if (!club.getName().equalsIgnoreCase(newName) && clubRepository.existsByName(newName)) {
            throw new ConflictException("A club with this name already exists.");
        }
        club.setName(newName);
        club.setDescription(request.description());
        club.setCategory(request.category());
        club.setLogoUrl(request.logoUrl());
        club.setCoverImageUrl(request.coverImageUrl());
        club.setContactEmail(request.contactEmail());
        club.setContactPhone(request.contactPhone());
        club = clubRepository.save(club);
        return toResponseWithCounts(club, userId);
    }

    @Override
    @Transactional
    public void deactivate(Long userId, Long clubId) {
        Club club = getClub(clubId);
        clubAccess.requireCoordinator(clubId, userId);
        club.setActive(false);
        clubRepository.save(club);
    }

    @Override
    @Transactional
    public void reactivate(Long userId, Long clubId) {
        Club club = getClub(clubId);
        clubAccess.requireCoordinator(clubId, userId);
        club.setActive(true);
        clubRepository.save(club);
    }

    @Override
    @Transactional(readOnly = true)
    public ClubResponse getById(Long clubId, Long viewerId) {
        return toResponseWithCounts(getClub(clubId), viewerId);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ClubResponse> search(String q, String category, Boolean active, Long viewerId, Pageable pageable) {
        Page<Club> page = clubRepository.findAll(ClubSpecifications.build(q, category, active), pageable);
        Set<Long> followedIds = viewerId != null ? clubFollowRepository.findClubIdsByUserId(viewerId) : Set.of();
        return PageResponse.from(page, club -> toResponseWithCounts(club, followedIds));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClubMemberResponse> listMembers(Long userId, Long clubId) {
        getClub(clubId);
        // Platform admins and active members may view the full member roster.
        clubAccess.requireAdminOrActiveMember(clubId, userId);
        return clubMemberRepository.findByClubId(clubId).stream()
                .map(ClubMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional
    public ClubMemberResponse join(Long userId, Long clubId) {
        Club club = getClub(clubId);
        if (!club.isActive()) {
            throw new BadRequestException("This club is not currently accepting members.");
        }
        if (clubMemberRepository.existsByClubIdAndUserId(clubId, userId)) {
            throw new ConflictException("You have already requested to join or are a member of this club.");
        }
        ClubMember membership = clubMemberRepository.save(ClubMember.builder()
                .club(club)
                .user(getUser(userId))
                .clubRole(ClubRole.MEMBER)
                .status(MembershipStatus.PENDING)
                .build());
        return ClubMapper.toMemberResponse(membership);
    }

    @Override
    @Transactional
    public ClubMemberResponse approveMember(Long actingUserId, Long clubId, Long membershipId) {
        clubAccess.requireCoordinator(clubId, actingUserId);
        ClubMember membership = getMembership(clubId, membershipId);
        membership.setStatus(MembershipStatus.ACTIVE);
        return ClubMapper.toMemberResponse(clubMemberRepository.save(membership));
    }

    @Override
    @Transactional
    public void removeMember(Long actingUserId, Long clubId, Long membershipId) {
        clubAccess.requireCoordinator(clubId, actingUserId);
        ClubMember membership = getMembership(clubId, membershipId);
        if (membership.getClubRole() == ClubRole.COORDINATOR && countActiveCoordinators(clubId) <= 1) {
            throw new BadRequestException("Cannot remove the last coordinator of the club.");
        }
        clubMemberRepository.delete(membership);
    }

    @Override
    @Transactional
    public void leave(Long userId, Long clubId) {
        ClubMember membership = clubMemberRepository.findByClubIdAndUserId(clubId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership", "club", clubId));
        if (membership.getClubRole() == ClubRole.COORDINATOR && countActiveCoordinators(clubId) <= 1) {
            throw new BadRequestException("Assign another coordinator before leaving the club.");
        }
        clubMemberRepository.delete(membership);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClubMemberResponse> myMemberships(Long userId) {
        return clubMemberRepository.findByUserId(userId).stream()
                .map(ClubMapper::toMemberResponse)
                .toList();
    }

    @Override
    @Transactional
    public void follow(Long userId, Long clubId) {
        Club club = getClub(clubId);
        // Idempotent: following an already-followed club is a no-op rather than a duplicate row.
        if (clubFollowRepository.existsByClubIdAndUserId(clubId, userId)) {
            return;
        }
        clubFollowRepository.save(ClubFollow.builder()
                .club(club)
                .user(getUser(userId))
                .build());
    }

    @Override
    @Transactional
    public void unfollow(Long userId, Long clubId) {
        // Idempotent: unfollowing a club you don't follow is a no-op.
        clubFollowRepository.findByClubIdAndUserId(clubId, userId)
                .ifPresent(clubFollowRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ClubResponse> myFollowedClubs(Long userId) {
        // Every club in this list is followed by the viewer, so following=true throughout.
        return clubFollowRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(ClubFollow::getClub)
                .map(club -> toResponseWithCounts(club, true))
                .toList();
    }

    // ---- helpers ----

    /** Single-club variant: resolves the viewer's follow state with a targeted existence check. */
    private ClubResponse toResponseWithCounts(Club club, Long viewerId) {
        boolean following = viewerId != null
                && clubFollowRepository.existsByClubIdAndUserId(club.getId(), viewerId);
        return toResponseWithCounts(club, following);
    }

    /** Listing variant: resolves follow state from a pre-fetched set of the viewer's followed club ids. */
    private ClubResponse toResponseWithCounts(Club club, Set<Long> followedIds) {
        return toResponseWithCounts(club, followedIds.contains(club.getId()));
    }

    private ClubResponse toResponseWithCounts(Club club, boolean following) {
        long members = clubMemberRepository.countByClubId(club.getId());
        long events = eventRepository.countByClubId(club.getId());
        long followers = clubFollowRepository.countByClubId(club.getId());
        return ClubMapper.toResponse(club, members, events, followers, following);
    }

    private long countActiveCoordinators(Long clubId) {
        return clubMemberRepository.findByClubIdAndClubRole(clubId, ClubRole.COORDINATOR).stream()
                .filter(m -> m.getStatus() == MembershipStatus.ACTIVE)
                .count();
    }

    private ClubMember getMembership(Long clubId, Long membershipId) {
        ClubMember membership = clubMemberRepository.findById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Membership", "id", membershipId));
        if (membership.getClub() == null || !membership.getClub().getId().equals(clubId)) {
            throw new ForbiddenException("This membership does not belong to the specified club.");
        }
        return membership;
    }

    private Club getClub(Long clubId) {
        return clubRepository.findById(clubId)
                .orElseThrow(() -> new ResourceNotFoundException("Club", "id", clubId));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}
