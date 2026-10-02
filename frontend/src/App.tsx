import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';

import { RootLayout } from '@/components/layout/RootLayout';
import { AppLayout } from '@/components/layout/AppLayout';
import { ProtectedRoute } from '@/components/routing/ProtectedRoute';
import { RoleRoute } from '@/components/routing/RoleRoute';
import { GuestRoute } from '@/components/routing/GuestRoute';
import { RoleHomeRedirect } from '@/components/routing/RoleHomeRedirect';
import { FullPageLoader } from '@/components/ui/FullPageLoader';

/*
 * Route-level code splitting keeps the initial bundle small — auth pages and
 * the public catalog load first; heavier coordinator/admin screens arrive on
 * demand.
 *
 * Two layout shells:
 *   • RootLayout — public + auth pages (top navbar only).
 *   • AppLayout  — the authenticated `/app/*` area (sidebar + slim topbar).
 */

// Public
const LandingPage = lazy(() => import('@/pages/LandingPage'));
const EventsListPage = lazy(() => import('@/pages/events/EventsListPage'));
const EventDetailPage = lazy(() => import('@/pages/events/EventDetailPage'));
const ClubsListPage = lazy(() => import('@/pages/clubs/ClubsListPage'));
const ClubDetailPage = lazy(() => import('@/pages/clubs/ClubDetailPage'));
const VerifyCertificatePage = lazy(() => import('@/pages/VerifyCertificatePage'));
const UnsubscribePage = lazy(() => import('@/pages/UnsubscribePage'));

// Auth
const LoginPage = lazy(() => import('@/pages/auth/LoginPage'));
const VerifyOtpPage = lazy(() => import('@/pages/auth/VerifyOtpPage'));
const RegisterPage = lazy(() => import('@/pages/auth/RegisterPage'));
const ForgotPasswordPage = lazy(() => import('@/pages/auth/ForgotPasswordPage'));
const ResetPasswordPage = lazy(() => import('@/pages/auth/ResetPasswordPage'));
const VerifyEmailPage = lazy(() => import('@/pages/auth/VerifyEmailPage'));

// Authenticated (any role)
const DashboardPage = lazy(() => import('@/pages/app/DashboardPage'));
const MyEventsPage = lazy(() => import('@/pages/app/MyEventsPage'));
const MySavedEventsPage = lazy(() => import('@/pages/app/MySavedEventsPage'));
const MyFollowingPage = lazy(() => import('@/pages/app/MyFollowingPage'));
const MyVolunteeringPage = lazy(() => import('@/pages/app/MyVolunteeringPage'));
const MyCertificatesPage = lazy(() => import('@/pages/app/MyCertificatesPage'));
const NotificationsPage = lazy(() => import('@/pages/app/NotificationsPage'));
const ProfilePage = lazy(() => import('@/pages/app/ProfilePage'));
const TeamDetailPage = lazy(() => import('@/pages/app/TeamDetailPage'));
const TrendingEventsPage = lazy(() => import('@/pages/app/TrendingEventsPage'));
const EventsCalendarPage = lazy(() => import('@/pages/app/EventsCalendarPage'));
const LeaderboardsPage = lazy(() => import('@/pages/app/LeaderboardsPage'));
const SearchResultsPage = lazy(() => import('@/pages/app/SearchResultsPage'));
// Attendee check-in scanner shared by club members and coordinators.
const ScanPage = lazy(() => import('@/pages/app/ScanPage'));

// Volunteer (self-service workspace)
const VolunteerDashboardPage = lazy(() => import('@/pages/volunteer/VolunteerDashboardPage'));
const VolunteerEventsPage = lazy(() => import('@/pages/volunteer/VolunteerEventsPage'));
const VolunteerTasksPage = lazy(() => import('@/pages/volunteer/VolunteerTasksPage'));
const VolunteerTaskDetailPage = lazy(() => import('@/pages/volunteer/VolunteerTaskDetailPage'));
const VolunteerAttendancePage = lazy(() => import('@/pages/volunteer/VolunteerAttendancePage'));
const VolunteerScanPage = lazy(() => import('@/pages/volunteer/VolunteerScanPage'));

// Coordinator
const ManageDashboardPage = lazy(() => import('@/pages/manage/ManageDashboardPage'));
const CoordinatorEventsPage = lazy(() => import('@/pages/manage/CoordinatorEventsPage'));
const CoordinatorClubsPage = lazy(() => import('@/pages/manage/CoordinatorClubsPage'));
const CoordinatorWorkspacePage = lazy(() => import('@/pages/manage/CoordinatorWorkspacePage'));
const ManageClubPage = lazy(() => import('@/pages/manage/ManageClubPage'));
const EventFormPage = lazy(() => import('@/pages/manage/EventFormPage'));
const ManageEventPage = lazy(() => import('@/pages/manage/ManageEventPage'));
const ManageCompetitionPage = lazy(() => import('@/pages/manage/ManageCompetitionPage'));

// Admin (full platform)
const AdminDashboardPage = lazy(() => import('@/pages/admin/AdminDashboardPage'));
const AdminUsersPage = lazy(() => import('@/pages/admin/AdminUsersPage'));
const AdminClubsPage = lazy(() => import('@/pages/admin/AdminClubsPage'));
const AdminEventsPage = lazy(() => import('@/pages/admin/AdminEventsPage'));
const AdminPaymentsPage = lazy(() => import('@/pages/admin/AdminPaymentsPage'));
const AdminCertificatesPage = lazy(() => import('@/pages/admin/AdminCertificatesPage'));
const AdminAuditLogPage = lazy(() => import('@/pages/admin/AdminAuditLogPage'));

// System
const ForbiddenPage = lazy(() => import('@/pages/system/ForbiddenPage'));
const NotFoundPage = lazy(() => import('@/pages/system/NotFoundPage'));

export default function App() {
  return (
    <Suspense fallback={<FullPageLoader />}>
      <Routes>
        {/* ===================== public + auth shell ===================== */}
        <Route element={<RootLayout />}>
          {/* ---------------------------- public ---------------------------- */}
          <Route index element={<LandingPage />} />
          <Route path="events" element={<EventsListPage />} />
          <Route path="events/:id" element={<EventDetailPage />} />
          <Route path="clubs" element={<ClubsListPage />} />
          <Route path="clubs/:id" element={<ClubDetailPage />} />
          <Route path="verify-certificate" element={<VerifyCertificatePage />} />
          <Route path="verify-certificate/:code" element={<VerifyCertificatePage />} />
          <Route path="unsubscribe" element={<UnsubscribePage />} />

          {/* ----------------------------- auth ----------------------------- */}
          <Route element={<GuestRoute />}>
            <Route path="login" element={<LoginPage />} />
            <Route path="register" element={<RegisterPage />} />
            <Route path="forgot-password" element={<ForgotPasswordPage />} />
            <Route path="reset-password" element={<ResetPasswordPage />} />
          </Route>
          {/* Reached mid-login carrying a challenge token in router state; it must
              stay accessible while signed-out and while a session is being issued,
              so it lives outside GuestRoute. */}
          <Route path="verify-otp" element={<VerifyOtpPage />} />
          <Route path="verify-email" element={<VerifyEmailPage />} />

          {/* ---------------------------- system ---------------------------- */}
          <Route path="403" element={<ForbiddenPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>

        {/* ================== authenticated app shell ==================== */}
        <Route path="app" element={<ProtectedRoute />}>
          <Route element={<AppLayout />}>
            <Route index element={<RoleHomeRedirect />} />
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="my-events" element={<MyEventsPage />} />
            <Route path="saved" element={<MySavedEventsPage />} />
            <Route path="following" element={<MyFollowingPage />} />
            <Route path="volunteering" element={<MyVolunteeringPage />} />
            <Route path="certificates" element={<MyCertificatesPage />} />
            <Route path="notifications" element={<NotificationsPage />} />
            <Route path="profile" element={<ProfilePage />} />
            <Route path="teams/:id" element={<TeamDetailPage />} />
            <Route path="trending" element={<TrendingEventsPage />} />
            <Route path="calendar" element={<EventsCalendarPage />} />
            <Route path="leaderboards" element={<LeaderboardsPage />} />
            {/* Authenticated variant so the sidebar shell stays in place; the
                public /verify-certificate route remains for signed-out visitors. */}
            <Route path="verify-certificate" element={<VerifyCertificatePage />} />
            <Route path="verify-certificate/:code" element={<VerifyCertificatePage />} />
            <Route path="search" element={<SearchResultsPage />} />

            {/* --------------------- coordinator only --------------------- */}
            <Route element={<RoleRoute allow={['CLUB_COORDINATOR']} />}>
              <Route path="manage" element={<ManageDashboardPage />} />
              {/* Coordinator management list pages (CRUD hubs). */}
              <Route path="manage/events" element={<CoordinatorEventsPage />} />
              <Route path="manage/clubs" element={<CoordinatorClubsPage />} />
              {/* Event-scoped workspace panels, surfaced as sidebar entries. */}
              <Route path="manage/overview" element={<CoordinatorWorkspacePage section="overview" />} />
              <Route path="manage/registrations" element={<CoordinatorWorkspacePage section="registrations" />} />
              <Route path="manage/attendance" element={<CoordinatorWorkspacePage section="attendance" />} />
              <Route path="manage/schedule" element={<CoordinatorWorkspacePage section="schedule" />} />
              <Route path="manage/volunteers" element={<CoordinatorWorkspacePage section="volunteers" />} />
              <Route path="manage/competitions" element={<CoordinatorWorkspacePage section="competitions" />} />
              <Route path="manage/certificates" element={<CoordinatorWorkspacePage section="certificates" />} />
              <Route path="manage/payments" element={<CoordinatorWorkspacePage section="payments" />} />
              <Route path="manage/announcements" element={<CoordinatorWorkspacePage section="announcements" />} />
              <Route path="manage/gallery" element={<CoordinatorWorkspacePage section="gallery" />} />
              <Route path="manage/discussion" element={<CoordinatorWorkspacePage section="discussion" />} />
              <Route path="manage/feedback" element={<CoordinatorWorkspacePage section="feedback" />} />
              <Route path="manage/clubs/:clubId" element={<ManageClubPage />} />
              <Route path="manage/events/new" element={<EventFormPage />} />
              <Route path="manage/events/:id/edit" element={<EventFormPage />} />
              <Route path="manage/events/:id" element={<ManageEventPage />} />
              <Route path="manage/competitions/:id" element={<ManageCompetitionPage />} />
            </Route>

            {/* ------- attendee check-in scanner (member + coordinator) ------- */}
            <Route element={<RoleRoute allow={['CLUB_MEMBER', 'CLUB_COORDINATOR']} />}>
              <Route path="scan" element={<ScanPage />} />
            </Route>

            {/* --------------------- volunteer only ----------------------- */}
            <Route element={<RoleRoute allow={['VOLUNTEER']} />}>
              <Route
                path="volunteer"
                element={<Navigate to="/app/volunteer/dashboard" replace />}
              />
              <Route path="volunteer/dashboard" element={<VolunteerDashboardPage />} />
              <Route path="volunteer/events" element={<VolunteerEventsPage />} />
              <Route path="volunteer/tasks" element={<VolunteerTasksPage />} />
              <Route path="volunteer/tasks/:taskId" element={<VolunteerTaskDetailPage />} />
              <Route path="volunteer/attendance" element={<VolunteerAttendancePage />} />
              <Route path="volunteer/scan" element={<VolunteerScanPage />} />
            </Route>

            {/* ------------------- admin (full platform) ------------------- */}
            <Route element={<RoleRoute allow={['ADMIN']} />}>
              <Route path="admin" element={<AdminDashboardPage />} />
              <Route path="admin/users" element={<AdminUsersPage />} />
              <Route path="admin/clubs" element={<AdminClubsPage />} />
              <Route path="admin/events" element={<AdminEventsPage />} />
              <Route path="admin/payments" element={<AdminPaymentsPage />} />
              <Route path="admin/certificates" element={<AdminCertificatesPage />} />
              <Route path="admin/audit-log" element={<AdminAuditLogPage />} />
            </Route>

            {/* App-scoped 404 keeps the sidebar shell for bad /app/* paths. */}
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
