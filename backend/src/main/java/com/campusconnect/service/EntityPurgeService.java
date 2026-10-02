package com.campusconnect.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Hard-deletes an event, a club or a user together with every row that
 * references it.
 *
 * <p>The schema is created with {@code ddl-auto=update}, which never emits
 * {@code ON DELETE CASCADE}, and the JPA associations intentionally carry no
 * cascade rules (so an accidental {@code save} can never wipe related data).
 * That means a plain {@code repository.delete(event)} throws a foreign-key
 * violation the moment any child row exists — which is why admin/coordinator
 * deletes appeared to "do nothing" (the violation was caught and turned into a
 * 409). This service removes the children in dependency order first, inside the
 * caller's transaction, so the final parent delete always succeeds.</p>
 *
 * <p>All statements are parameterised JPQL bulk deletes keyed by id — no user
 * input is interpolated into the query text.</p>
 */
@Service
public class EntityPurgeService {

    /** Guards catalog-derived identifiers before they are ever placed in SQL text. */
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z0-9_]+$");

    @PersistenceContext
    private EntityManager em;

    /**
     * Delete a single event and all of its dependent rows.
     * Ordered from the deepest descendants up to the event itself.
     */
    @Transactional
    public void purgeEvent(Long eventId) {
        // Competition subtree: scores -> rounds/judges -> competitions
        exec("delete from Score s where s.round.id in "
                + "(select r.id from CompetitionRound r where r.competition.id in "
                + "(select c.id from Competition c where c.event.id = :id))", eventId);
        exec("delete from CompetitionRound r where r.competition.id in "
                + "(select c.id from Competition c where c.event.id = :id)", eventId);
        exec("delete from Judge j where j.competition.id in "
                + "(select c.id from Competition c where c.event.id = :id)", eventId);
        exec("delete from Competition c where c.event.id = :id", eventId);

        // Volunteer subtree: event-scoped rows -> volunteers.
        exec("delete from VolunteerAssignment a where a.event.id = :id", eventId);
        exec("delete from VolunteerAttendance a where a.event.id = :id", eventId);
        exec("delete from VolunteerTask t where t.event.id = :id", eventId);
        // Older databases may still retain volunteers.event_id from the previous
        // event-scoped volunteer model. Remove those legacy profiles and their
        // dependents before deleting the event row. Fresh schemas do not have this
        // column, so check before preparing SQL that references it.
        if (hasLegacyVolunteerEventColumn()) {
            execNative("delete from volunteer_tasks where volunteer_id in "
                    + "(select id from volunteers where event_id = :id)", eventId);
            execNative("delete from volunteer_attendance where volunteer_id in "
                    + "(select id from volunteers where event_id = :id)", eventId);
            execNative("delete from volunteer_assignments where volunteer_id in "
                    + "(select id from volunteers where event_id = :id)", eventId);
            execNative("delete from volunteers where event_id = :id", eventId);
        }

        // Rows that reference a registration must go before the registrations.
        exec("delete from Attendance a where a.event.id = :id", eventId);
        exec("delete from Payment p where p.event.id = :id", eventId);
        exec("delete from Registration r where r.event.id = :id", eventId);

        // Team subtree: members -> teams (registrations already gone).
        exec("delete from TeamMember tm where tm.team.id in "
                + "(select t.id from Team t where t.event.id = :id)", eventId);
        exec("delete from Team t where t.event.id = :id", eventId);

        // Remaining direct children of the event.
        exec("delete from Certificate c where c.event.id = :id", eventId);
        exec("delete from CertificateTemplate ct where ct.event.id = :id", eventId);
        exec("delete from Feedback f where f.event.id = :id", eventId);
        exec("delete from EventSchedule s where s.event.id = :id", eventId);
        exec("delete from SavedEvent se where se.event.id = :id", eventId);
        exec("delete from Media m where m.event.id = :id", eventId);
        exec("delete from Announcement a where a.event.id = :id", eventId);

        // Comments self-reference via parent_id, so remove replies before roots.
        exec("delete from Comment c where c.event.id = :id and c.parent is not null", eventId);
        exec("delete from Comment c where c.event.id = :id", eventId);

        exec("delete from Event e where e.id = :id", eventId);

        // Bulk JPQL deletes bypass the persistence context, so a managed copy of any row
        // deleted above (e.g. the caller's just-loaded Event) may linger detached-but-present.
        // Clear it so the outer transaction's commit-time flush has nothing stale to process.
        em.clear();
    }

    /**
     * Disband a single team: remove the team together with everything that
     * references it — competition scores about the team, the attendance and
     * payment rows tied to the team's registrations, the registrations
     * themselves, and the team memberships — in dependency order, then the team
     * row. (No {@code ON DELETE CASCADE} exists, so each child must go first.)
     */
    @Transactional
    public void purgeTeam(Long teamId) {
        // Competition scores that reference the team.
        exec("delete from Score s where s.team.id = :id", teamId);
        // Rows that reference the team's registrations must go before them.
        exec("delete from Attendance a where a.registration.id in "
                + "(select r.id from Registration r where r.team.id = :id)", teamId);
        exec("delete from Payment p where p.registration.id in "
                + "(select r.id from Registration r where r.team.id = :id)", teamId);
        exec("delete from Registration r where r.team.id = :id", teamId);
        // Memberships, then the team itself.
        exec("delete from TeamMember tm where tm.team.id = :id", teamId);
        exec("delete from Team t where t.id = :id", teamId);
    }

    /**
     * Delete a single competition together with its scoring subtree — scores
     * first (they reference rounds and judges), then the rounds and judges, then
     * the competition row itself. No {@code ON DELETE CASCADE} exists, so each
     * child must be removed in dependency order or the final delete trips a
     * foreign-key violation (surfaced to the user as a 409 / "unexpected error").
     */
    @Transactional
    public void purgeCompetition(Long competitionId) {
        exec("delete from Score s where s.round.id in "
                + "(select r.id from CompetitionRound r where r.competition.id = :id)", competitionId);
        exec("delete from CompetitionRound r where r.competition.id = :id", competitionId);
        exec("delete from Judge j where j.competition.id = :id", competitionId);
        exec("delete from Competition c where c.id = :id", competitionId);
        em.clear();
    }

    /**
     * Delete a club and everything under it, including all of its events.
     */
    @Transactional
    public void purgeClub(Long clubId) {
        List<Long> eventIds = em.createQuery(
                        "select e.id from Event e where e.club.id = :id", Long.class)
                .setParameter("id", clubId)
                .getResultList();
        for (Long eventId : eventIds) {
            purgeEvent(eventId);
        }

        // Club-scoped volunteer roster: dependent rows -> volunteer profiles.
        // Volunteers belong to the club (not an event), so purging the events
        // above does not remove them; clear their remaining children first.
        exec("delete from VolunteerTask t where t.volunteer.id in "
                + "(select v.id from Volunteer v where v.club.id = :id)", clubId);
        exec("delete from VolunteerAttendance a where a.volunteer.id in "
                + "(select v.id from Volunteer v where v.club.id = :id)", clubId);
        exec("delete from VolunteerAssignment a where a.volunteer.id in "
                + "(select v.id from Volunteer v where v.club.id = :id)", clubId);
        exec("delete from Volunteer v where v.club.id = :id", clubId);

        exec("delete from ClubMember cm where cm.club.id = :id", clubId);
        exec("delete from ClubFollow cf where cf.club.id = :id", clubId);
        exec("delete from Media m where m.club.id = :id", clubId);
        exec("delete from Announcement a where a.club.id = :id", clubId);

        exec("delete from Club c where c.id = :id", clubId);
        em.clear();
    }

    /**
     * Delete a user account and every row that references it, in dependency
     * order, so the final {@code delete User} never trips a foreign-key
     * violation. The schema has no {@code ON DELETE CASCADE}, so each child /
     * grandchild table is cleared explicitly here.
     *
     * <p>Two deliberate asymmetries:</p>
     * <ul>
     *   <li><b>Content the user created but that belongs to the platform or
     *   other people is preserved by nulling the "author/creator/marked-by"
     *   pointer</b> — clubs, events, announcements, gallery media, volunteer
     *   assignments/tasks and attendance rows the user marked for <em>other</em>
     *   participants all survive.</li>
     *   <li><b>Rows whose owning FK is NOT NULL are deleted</b> — a user's own
     *   registrations, memberships, certificates, payments, teams they lead,
     *   etc. Teams the user leads are removed together with their members and
     *   scores, but other members' registrations are merely detached (their
     *   {@code team_id} is nulled) rather than destroyed.</li>
     * </ul>
     */
    @Transactional
    public void purgeUser(Long userId) {
        // --- Phase A: preserve shared/other-user data by nulling optional pointers ---
        exec("update Event e set e.createdBy = null where e.createdBy.id = :id", userId);
        exec("update Club c set c.createdBy = null where c.createdBy.id = :id", userId);
        exec("update Announcement a set a.author = null where a.author.id = :id", userId);
        exec("update Media m set m.uploadedBy = null where m.uploadedBy.id = :id", userId);
        exec("update VolunteerAssignment a set a.assignedBy = null where a.assignedBy.id = :id", userId);
        exec("update VolunteerTask t set t.assignedBy = null where t.assignedBy.id = :id", userId);
        // Keep other participants' attendance rows; just detach who marked them.
        exec("update Attendance a set a.markedBy = null where a.markedBy.id = :id and a.user.id <> :id", userId);

        // --- Phase B: grandchildren (rows that reference the user's own child rows) ---
        // Scores: cast by the user as judge, about the user, or for the user's teams.
        exec("delete from Score s where s.judge.id in (select j.id from Judge j where j.user.id = :id)", userId);
        exec("delete from Score s where s.participant.id = :id", userId);
        exec("delete from Score s where s.team.id in (select t.id from Team t where t.leader.id = :id)", userId);
        // Volunteer profile subtree (tasks/attendance/assignments) before the profile.
        exec("delete from VolunteerTask t where t.volunteer.id in (select v.id from Volunteer v where v.user.id = :id)", userId);
        exec("delete from VolunteerAttendance a where a.volunteer.id in (select v.id from Volunteer v where v.user.id = :id)", userId);
        exec("delete from VolunteerAssignment a where a.volunteer.id in (select v.id from Volunteer v where v.user.id = :id)", userId);
        // Attendance + payments tied to the user (must go before the registrations).
        exec("delete from Attendance a where a.user.id = :id", userId);
        // Also clear any attendance keyed to the user's own registrations regardless of
        // who the recorded attendee is (mirrors purgeEvent/purgeTeam), so a stray row can
        // never block the registration delete below.
        exec("delete from Attendance a where a.registration.id in (select r.id from Registration r where r.user.id = :id)", userId);
        exec("delete from Payment p where p.registration.id in (select r.id from Registration r where r.user.id = :id)", userId);
        exec("delete from Payment p where p.user.id = :id", userId);
        // Teams the user leads: detach OTHER users' registrations, then drop members.
        exec("update Registration r set r.team = null where r.team.id in "
                + "(select t.id from Team t where t.leader.id = :id) and r.user.id <> :id", userId);
        exec("delete from TeamMember tm where tm.team.id in (select t.id from Team t where t.leader.id = :id)", userId);
        // Comments self-reference: remove replies to the user's comments, then the user's own replies.
        List<Long> replyIds = em.createQuery(
                        "select c.id from Comment c where c.parent.id in "
                                + "(select p.id from Comment p where p.author.id = :id)", Long.class)
                .setParameter("id", userId)
                .getResultList();
        if (!replyIds.isEmpty()) {
            em.createQuery("delete from Comment c where c.id in :ids")
                    .setParameter("ids", replyIds)
                    .executeUpdate();
        }
        exec("delete from Comment c where c.author.id = :id and c.parent is not null", userId);

        // --- Phase C: direct children whose FK to the user is NOT NULL ---
        exec("delete from NotificationPreference np where np.user.id = :id", userId);
        exec("delete from Notification n where n.recipient.id = :id", userId);
        exec("delete from SavedEvent se where se.user.id = :id", userId);
        exec("delete from ClubFollow cf where cf.user.id = :id", userId);
        exec("delete from ClubMember cm where cm.user.id = :id", userId);
        exec("delete from Feedback f where f.user.id = :id", userId);
        exec("delete from Certificate c where c.user.id = :id", userId);
        // The user's memberships in OTHER people's teams.
        exec("delete from TeamMember tm where tm.user.id = :id", userId);
        // Registrations are now free of attendance/payment references.
        exec("delete from Registration r where r.user.id = :id", userId);
        // Judge rows are now free of scores.
        exec("delete from Judge j where j.user.id = :id", userId);
        // Teams the user leads are now free of members/registrations/scores.
        exec("delete from Team t where t.leader.id = :id", userId);
        // The user's remaining (root) comments, now free of replies.
        exec("delete from Comment c where c.author.id = :id", userId);
        // Volunteer profiles are now free of tasks/attendance/assignments.
        exec("delete from Volunteer v where v.user.id = :id", userId);

        // --- Phase D: the user row itself ---
        // Safety net before the final delete: neutralise any row that STILL references
        // the user through a foreign key the explicit purge above did not clear. On a
        // schema evolved via ddl-auto=update, columns/tables from earlier versions (e.g.
        // the previous event-scoped volunteer model) can linger with live FK constraints
        // that the current entity model no longer maps. Because every mapped child was
        // already removed above, this only ever touches such leftover/legacy references.
        clearRemainingReferences("users", userId);
        exec("delete from User u where u.id = :id", userId);
        em.clear();
    }

    private void exec(String jpql, Long id) {
        em.createQuery(jpql).setParameter("id", id).executeUpdate();
    }

    /**
     * Neutralise every remaining row that references {@code parentTable(id)} via a
     * foreign key, by asking the live database catalog which columns actually point at
     * that table and clearing each one — nulled when the column is nullable (so shared
     * or authored content is preserved), deleted otherwise.
     *
     * <p>This is a defensive final step for schemas evolved with {@code ddl-auto=update}:
     * a foreign-key constraint created by an earlier version of the model is never
     * dropped automatically, so a column the current entities no longer map can still
     * block a parent delete. {@code information_schema} enumerates exactly the FK
     * constraints capable of blocking the delete, so this clears precisely those.</p>
     *
     * <p>Security: table and column names originate from {@code information_schema} — the
     * database's own catalog, never request input — and are additionally validated against
     * a strict identifier pattern before being interpolated. The id value is always bound
     * as a parameter, so no untrusted text ever reaches the SQL.</p>
     */
    @SuppressWarnings("unchecked")
    private void clearRemainingReferences(String parentTable, Long id) {
        // A few passes so a shallow chain (a leftover child that is itself referenced)
        // can resolve; stops as soon as a pass changes nothing.
        for (int pass = 0; pass < 3; pass++) {
            List<Object[]> refs = em.createNativeQuery(
                            "select k.TABLE_NAME, k.COLUMN_NAME, c.IS_NULLABLE "
                                    + "from information_schema.KEY_COLUMN_USAGE k "
                                    + "join information_schema.COLUMNS c "
                                    + "  on c.TABLE_SCHEMA = k.TABLE_SCHEMA "
                                    + " and c.TABLE_NAME  = k.TABLE_NAME "
                                    + " and c.COLUMN_NAME = k.COLUMN_NAME "
                                    + "where k.REFERENCED_TABLE_SCHEMA = database() "
                                    + "  and k.REFERENCED_TABLE_NAME = :parent "
                                    + "  and k.TABLE_SCHEMA = database()")
                    .setParameter("parent", parentTable)
                    .getResultList();

            int affected = 0;
            // Null the optional references first, then delete the mandatory ones.
            for (Object[] ref : refs) {
                if (isNullable(ref) && valid(ref, parentTable)) {
                    affected += em.createNativeQuery("update `" + ref[0] + "` set `" + ref[1]
                                    + "` = null where `" + ref[1] + "` = :id")
                            .setParameter("id", id).executeUpdate();
                }
            }
            for (Object[] ref : refs) {
                if (!isNullable(ref) && valid(ref, parentTable)) {
                    affected += em.createNativeQuery("delete from `" + ref[0]
                                    + "` where `" + ref[1] + "` = :id")
                            .setParameter("id", id).executeUpdate();
                }
            }
            if (affected == 0) {
                break;
            }
        }
    }

    private boolean isNullable(Object[] ref) {
        return "YES".equalsIgnoreCase(String.valueOf(ref[2]));
    }

    /** True only for a well-formed (table, column) that is not the parent table itself. */
    private boolean valid(Object[] ref, String parentTable) {
        String table = String.valueOf(ref[0]);
        String column = String.valueOf(ref[1]);
        return !table.equals(parentTable)
                && SAFE_IDENTIFIER.matcher(table).matches()
                && SAFE_IDENTIFIER.matcher(column).matches();
    }

        private void execNative(String sql, Long id) {
                em.createNativeQuery(sql).setParameter("id", id).executeUpdate();
        }

        private boolean hasLegacyVolunteerEventColumn() {
                Number count = (Number) em.createNativeQuery(
                                "select count(*) from information_schema.columns "
                                                + "where table_schema = database() and table_name = 'volunteers' "
                                                + "and column_name = 'event_id'")
                                .getSingleResult();
                return count.longValue() > 0;
        }
}
