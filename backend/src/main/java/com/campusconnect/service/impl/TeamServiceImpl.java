package com.campusconnect.service.impl;

import com.campusconnect.dto.request.AddTeamMemberRequest;
import com.campusconnect.dto.request.TeamRequest;
import com.campusconnect.dto.response.TeamMemberResponse;
import com.campusconnect.dto.response.TeamResponse;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Team;
import com.campusconnect.entity.TeamMember;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.EventStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.TeamMapper;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.TeamMemberRepository;
import com.campusconnect.repository.TeamRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.service.EntityPurgeService;
import com.campusconnect.service.TeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class TeamServiceImpl implements TeamService {

    private static final Set<EventStatus> TEAM_FORMING =
            Set.of(EventStatus.PUBLISHED, EventStatus.UPCOMING, EventStatus.ONGOING);

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final EntityPurgeService entityPurgeService;

    @Override
    @Transactional
    public TeamResponse create(Long userId, TeamRequest request) {
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));

        if (!event.isTeamEvent()) {
            throw new BadRequestException("This event does not support team registration.");
        }
        if (!TEAM_FORMING.contains(event.getStatus())) {
            throw new BadRequestException("Teams cannot be created for this event right now.");
        }
        if (teamRepository.existsByEventIdAndName(event.getId(), request.name())) {
            throw new ConflictException("A team with that name already exists for this event.");
        }
        if (teamMemberRepository.existsByTeam_Event_IdAndUserId(event.getId(), userId)) {
            throw new ConflictException("You are already part of a team for this event.");
        }

        User leader = getUser(userId);
        Integer maxSize = resolveMaxSize(event, request.maxSize());

        Team team = Team.builder()
                .name(request.name().trim())
                .event(event)
                .leader(leader)
                .maxSize(maxSize)
                .build();
        team = teamRepository.save(team);

        TeamMember leaderMembership = TeamMember.builder().team(team).user(leader).build();
        teamMemberRepository.save(leaderMembership);

        return TeamMapper.toResponse(team, List.of(leaderMembership));
    }

    @Override
    @Transactional(readOnly = true)
    public TeamResponse getById(Long teamId) {
        Team team = getTeam(teamId);
        return TeamMapper.toResponse(team, teamMemberRepository.findByTeamId(teamId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamResponse> listByEvent(Long eventId) {
        if (!eventRepository.existsById(eventId)) {
            throw new ResourceNotFoundException("Event", "id", eventId);
        }
        return teamRepository.findByEventId(eventId).stream()
                .map(team -> TeamMapper.toResponse(team, teamMemberRepository.findByTeamId(team.getId())))
                .toList();
    }

    @Override
    @Transactional
    public TeamMemberResponse addMember(Long actingUserId, Long teamId, AddTeamMemberRequest request) {
        Team team = getTeam(teamId);
        requireLeader(team, actingUserId);

        Event event = team.getEvent();
        if (event != null && !TEAM_FORMING.contains(event.getStatus())) {
            throw new BadRequestException("Members can no longer be added to teams for this event.");
        }

        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.email()));

        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, user.getId())) {
            throw new ConflictException("That user is already a member of this team.");
        }
        Long eventId = event != null ? event.getId() : null;
        if (eventId != null && teamMemberRepository.existsByTeam_Event_IdAndUserId(eventId, user.getId())) {
            throw new ConflictException("That user is already part of another team for this event.");
        }
        if (team.getMaxSize() != null && teamMemberRepository.countByTeamId(teamId) >= team.getMaxSize()) {
            throw new BadRequestException("This team is already full.");
        }

        TeamMember membership = TeamMember.builder().team(team).user(user).build();
        membership = teamMemberRepository.save(membership);
        Long leaderId = team.getLeader() != null ? team.getLeader().getId() : null;
        return TeamMapper.toMemberResponse(membership, leaderId);
    }

    @Override
    @Transactional
    public void removeMember(Long actingUserId, Long teamId, Long membershipId) {
        Team team = getTeam(teamId);
        requireLeader(team, actingUserId);

        TeamMember membership = teamMemberRepository.findById(membershipId)
                .orElseThrow(() -> new ResourceNotFoundException("Team member", "id", membershipId));
        if (membership.getTeam() == null || !membership.getTeam().getId().equals(teamId)) {
            throw new BadRequestException("That membership does not belong to this team.");
        }
        Long leaderId = team.getLeader() != null ? team.getLeader().getId() : null;
        if (membership.getUser() != null && membership.getUser().getId().equals(leaderId)) {
            throw new BadRequestException("The team leader cannot be removed. Delete the team instead.");
        }
        teamMemberRepository.delete(membership);
    }

    @Override
    @Transactional
    public void deleteTeam(Long actingUserId, Long teamId) {
        Team team = getTeam(teamId);
        requireLeader(team, actingUserId);

        // Disband: remove the team and everything tied to it — its registrations
        // (and their attendance/payment rows), competition scores and
        // memberships — in FK order. Previously this blocked whenever any
        // registration existed, but every team is auto-registered on creation
        // and cancellation is a soft delete, so the guard could never clear and
        // disbanding always failed with a 400.
        entityPurgeService.purgeTeam(teamId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<TeamResponse> myTeams(Long userId) {
        return teamMemberRepository.findByUserId(userId).stream()
                .map(TeamMember::getTeam)
                .filter(team -> team != null)
                .map(team -> TeamMapper.toResponse(team, teamMemberRepository.findByTeamId(team.getId())))
                .toList();
    }

    // ---- helpers ----

    private Integer resolveMaxSize(Event event, Integer requested) {
        Integer eventMax = event.getMaxTeamSize();
        if (requested == null) {
            return eventMax;
        }
        if (eventMax != null && requested > eventMax) {
            throw new BadRequestException("Team size cannot exceed the event maximum of " + eventMax + ".");
        }
        Integer eventMin = event.getMinTeamSize();
        if (eventMin != null && requested < eventMin) {
            throw new BadRequestException("Team size cannot be smaller than the event minimum of " + eventMin + ".");
        }
        return requested;
    }

    private void requireLeader(Team team, Long userId) {
        Long leaderId = team.getLeader() != null ? team.getLeader().getId() : null;
        if (!userId.equals(leaderId)) {
            throw new ForbiddenException("Only the team leader can perform this action.");
        }
    }

    private Team getTeam(Long teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team", "id", teamId));
    }

    private User getUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
    }
}
