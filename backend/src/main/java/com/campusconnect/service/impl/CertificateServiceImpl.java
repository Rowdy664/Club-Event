package com.campusconnect.service.impl;

import com.campusconnect.dto.request.CertificateBulkIssueRequest;
import com.campusconnect.dto.request.CertificateIssueRequest;
import com.campusconnect.dto.request.CertificateTemplateRequest;
import com.campusconnect.dto.response.CertificateBatchResponse;
import com.campusconnect.dto.response.CertificateParticipantResponse;
import com.campusconnect.dto.response.CertificateResponse;
import com.campusconnect.dto.response.CertificateTemplateResponse;
import com.campusconnect.dto.response.CertificateVerificationResponse;
import com.campusconnect.entity.Certificate;
import com.campusconnect.entity.CertificateTemplate;
import com.campusconnect.entity.Event;
import com.campusconnect.entity.Registration;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.CertificateRecipientScope;
import com.campusconnect.entity.enums.CertificateType;
import com.campusconnect.entity.enums.NotificationType;
import com.campusconnect.entity.enums.PaymentStatus;
import com.campusconnect.entity.enums.RegistrationStatus;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.mapper.CertificateMapper;
import com.campusconnect.mapper.CertificateTemplateMapper;
import com.campusconnect.repository.AttendanceRepository;
import com.campusconnect.repository.CertificateRepository;
import com.campusconnect.repository.CertificateTemplateRepository;
import com.campusconnect.repository.EventRepository;
import com.campusconnect.repository.PaymentRepository;
import com.campusconnect.repository.RegistrationRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.ClubAccess;
import com.campusconnect.service.CertificateService;
import com.campusconnect.service.EmailService;
import com.campusconnect.service.NotificationService;
import com.campusconnect.service.QrCodeService;
import com.campusconnect.service.storage.LocalStorageService;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
@Slf4j
public class CertificateServiceImpl implements CertificateService {

    private static final int QR_SIZE = 220;
    private static final float QR_DISPLAY = 84f;
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH).withZone(ZoneId.of("UTC"));

    private final CertificateRepository certificateRepository;
    private final CertificateTemplateRepository certificateTemplateRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final RegistrationRepository registrationRepository;
    private final AttendanceRepository attendanceRepository;
    private final PaymentRepository paymentRepository;
    private final ClubAccess clubAccess;
    private final NotificationService notificationService;
    private final QrCodeService qrCodeService;
    private final EmailService emailService;
    private final String frontendBaseUrl;
    // Always injectable (LocalStorageService is a @Component even when S3 is active); used to resolve
    // relative "/api/files/..." background URLs to bytes without an HTTP self-call.
    private final LocalStorageService localStorageService;
    private final String localPublicBaseUrl;

    public CertificateServiceImpl(CertificateRepository certificateRepository,
                                  CertificateTemplateRepository certificateTemplateRepository,
                                  EventRepository eventRepository,
                                  UserRepository userRepository,
                                  RegistrationRepository registrationRepository,
                                  AttendanceRepository attendanceRepository,
                                  PaymentRepository paymentRepository,
                                  ClubAccess clubAccess,
                                  NotificationService notificationService,
                                  QrCodeService qrCodeService,
                                  EmailService emailService,
                                  LocalStorageService localStorageService,
                                  @Value("${app.storage.local.public-base-url:/api/files}") String localPublicBaseUrl,
                                  @Value("${app.frontend.base-url}") String frontendBaseUrl) {
        this.certificateRepository = certificateRepository;
        this.certificateTemplateRepository = certificateTemplateRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.registrationRepository = registrationRepository;
        this.attendanceRepository = attendanceRepository;
        this.paymentRepository = paymentRepository;
        this.clubAccess = clubAccess;
        this.notificationService = notificationService;
        this.qrCodeService = qrCodeService;
        this.emailService = emailService;
        this.localStorageService = localStorageService;
        this.localPublicBaseUrl = localPublicBaseUrl;
        this.frontendBaseUrl = frontendBaseUrl;
    }

    @Override
    @Transactional
    public CertificateResponse issue(Long actingUserId, CertificateIssueRequest request) {
        Event event = eventRepository.findById(request.eventId())
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", request.eventId()));
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", request.email()));

        CertificateType type = request.type() != null ? request.type() : CertificateType.PARTICIPATION;
        if (certificateRepository.existsByUserIdAndEventIdAndType(user.getId(), event.getId(), type)) {
            throw new ConflictException("This participant already has a " + type + " certificate for this event.");
        }

        CertificateTemplate template = certificateTemplateRepository.findByEventId(event.getId()).orElse(null);
        String title = resolveTitle(request.title(), template, type);

        Certificate certificate = Certificate.builder()
                .user(user)
                .event(event)
                .type(type)
                .title(title)
                .certificateCode(generateCode())
                .issuedAt(Instant.now())
                .build();
        certificate = certificateRepository.save(certificate);

        notifyIssued(user.getId(), title, event.getTitle());

        return CertificateMapper.toResponse(certificate, verifyUrl(certificate.getCertificateCode()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<CertificateResponse> myCertificates(Long userId) {
        return certificateRepository.findByUserId(userId).stream()
                .map(c -> CertificateMapper.toResponse(c, verifyUrl(c.getCertificateCode())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<CertificateResponse> eventCertificates(Long actingUserId, Long eventId) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        return certificateRepository.findByEventId(eventId).stream()
                .map(c -> CertificateMapper.toResponse(c, verifyUrl(c.getCertificateCode())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CertificateVerificationResponse verify(String certificateCode) {
        return certificateRepository.findByCertificateCode(certificateCode)
                .map(CertificateMapper::toVerification)
                .orElseGet(CertificateVerificationResponse::invalid);
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderPdf(Long actingUserId, Long certificateId) {
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate", "id", certificateId));

        boolean owner = certificate.getUser() != null && certificate.getUser().getId().equals(actingUserId);
        boolean coordinator = certificate.getEvent() != null && certificate.getEvent().getClub() != null
                && clubAccess.isAdminOrCoordinator(certificate.getEvent().getClub().getId(), actingUserId);
        if (!owner && !coordinator) {
            throw new ForbiddenException("You are not allowed to download this certificate.");
        }
        return buildPdf(certificate, resolveTemplate(certificate.getEvent()));
    }

    @Override
    @Transactional
    public CertificateResponse revoke(Long actingUserId, Long certificateId) {
        Certificate certificate = certificateRepository.findById(certificateId)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate", "id", certificateId));
        if (certificate.getEvent() == null || certificate.getEvent().getClub() == null) {
            throw new BadRequestException("This certificate is not associated with a club event.");
        }
        clubAccess.requireAdminOrCoordinator(certificate.getEvent().getClub().getId(), actingUserId);

        if (!certificate.isRevoked()) {
            certificate.setRevoked(true);
            certificate.setRevokedAt(Instant.now());
            certificate = certificateRepository.save(certificate);

            if (certificate.getUser() != null) {
                String eventTitle = certificate.getEvent() != null ? certificate.getEvent().getTitle() : "an event";
                notificationService.notifyUser(certificate.getUser().getId(), NotificationType.GENERAL,
                        "Certificate revoked",
                        "Your \"" + certificate.getTitle() + "\" for \"" + eventTitle + "\" has been revoked.",
                        "/certificates");
            }
        }
        return CertificateMapper.toResponse(certificate, verifyUrl(certificate.getCertificateCode()));
    }

    @Override
    @Transactional(readOnly = true)
    public byte[] renderVerifiedPdf(String certificateCode) {
        Certificate certificate = certificateRepository.findByCertificateCode(certificateCode)
                .orElseThrow(() -> new ResourceNotFoundException("Certificate", "code", certificateCode));
        if (certificate.isRevoked()) {
            throw new BadRequestException("This certificate has been revoked and can no longer be downloaded.");
        }
        return buildPdf(certificate, resolveTemplate(certificate.getEvent()));
    }

    // ---- templates ----

    @Override
    @Transactional(readOnly = true)
    public CertificateTemplateResponse getEventTemplate(Long actingUserId, Long eventId) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);
        return certificateTemplateRepository.findByEventId(eventId)
                .map(CertificateTemplateMapper::toResponse)
                .orElse(null);
    }

    @Override
    @Transactional
    public CertificateTemplateResponse saveEventTemplate(Long actingUserId, Long eventId,
                                                         CertificateTemplateRequest request) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateTemplate template = certificateTemplateRepository.findByEventId(eventId)
                .orElseGet(CertificateServiceImpl::newTemplate);
        CertificateTemplateMapper.apply(request, template);
        template.setEvent(event);
        template = certificateTemplateRepository.save(template);
        return CertificateTemplateMapper.toResponse(template);
    }

    // ---- bulk issuing ----

    @Override
    @Transactional
    public CertificateBatchResponse generateForEvent(Long actingUserId, Long eventId,
                                                     CertificateRecipientScope scope, CertificateType type) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateRecipientScope effectiveScope = scope != null ? scope : CertificateRecipientScope.REGISTERED;
        CertificateType effectiveType = type != null ? type : CertificateType.PARTICIPATION;
        CertificateTemplate template = certificateTemplateRepository.findByEventId(eventId).orElse(null);
        return generateInternal(event, template, effectiveScope, effectiveType);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CertificateParticipantResponse> eligibleParticipants(Long actingUserId, Long eventId,
                                                                     CertificateRecipientScope scope,
                                                                     CertificateType type) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateRecipientScope effectiveScope = scope != null ? scope : CertificateRecipientScope.REGISTERED;
        CertificateType effectiveType = type != null ? type : CertificateType.PARTICIPATION;

        List<User> recipients = distinctRecipients(recipientsFor(event, effectiveScope));
        Set<Long> attendedIds = attendanceRepository.findByEventId(event.getId()).stream()
                .filter(a -> a.getUser() != null)
                .map(a -> a.getUser().getId())
                .collect(Collectors.toSet());
        Set<Long> paidIds = paymentRepository.findByEventIdAndStatus(event.getId(), PaymentStatus.SUCCESS).stream()
                .filter(p -> p.getUser() != null)
                .map(p -> p.getUser().getId())
                .collect(Collectors.toSet());

        return recipients.stream()
                .map(u -> new CertificateParticipantResponse(
                        u.getId(),
                        u.getFullName(),
                        u.getEmail(),
                        attendedIds.contains(u.getId()),
                        paidIds.contains(u.getId()),
                        certificateRepository.existsByUserIdAndEventIdAndType(u.getId(), event.getId(), effectiveType)))
                .sorted((a, b) -> a.fullName().compareToIgnoreCase(b.fullName()))
                .toList();
    }

    @Override
    @Transactional
    public CertificateBatchResponse generateForParticipants(Long actingUserId, Long eventId,
                                                            CertificateBulkIssueRequest request) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateType type = request.type() != null ? request.type() : CertificateType.PARTICIPATION;
        CertificateTemplate template = certificateTemplateRepository.findByEventId(eventId).orElse(null);

        // The set of ids the client is *allowed* to target: the event's registered participants.
        // Anything outside this set is rejected, so a coordinator can never issue to an arbitrary user.
        Map<Long, User> registered = distinctRecipients(
                recipientsFor(event, CertificateRecipientScope.REGISTERED)).stream()
                .collect(Collectors.toMap(User::getId, u -> u, (a, b) -> a, LinkedHashMap::new));

        boolean gatePayment = template != null && template.isRequirePayment() && event.isPaidEvent();
        Set<Long> paidUserIds = gatePayment
                ? paymentRepository.findByEventIdAndStatus(event.getId(), PaymentStatus.SUCCESS).stream()
                        .filter(p -> p.getUser() != null)
                        .map(p -> p.getUser().getId())
                        .collect(Collectors.toSet())
                : Set.of();

        String title = resolveTitle(null, template, type);

        int issued = 0;
        int skipped = 0;
        List<String> reasons = new ArrayList<>();
        Set<Long> processed = new HashSet<>();

        for (Long userId : request.userIds()) {
            if (userId == null || !processed.add(userId)) {
                continue; // ignore nulls and duplicate ids in the request
            }
            User user = registered.get(userId);
            if (user == null) {
                skipped++;
                reasons.add("User " + userId + " — not a participant of this event");
                continue;
            }
            if (certificateRepository.existsByUserIdAndEventIdAndType(user.getId(), event.getId(), type)) {
                skipped++;
                continue; // already has one — silent skip
            }
            if (gatePayment && !paidUserIds.contains(user.getId())) {
                skipped++;
                reasons.add(user.getFullName() + " — payment not completed");
                continue;
            }
            Certificate certificate = Certificate.builder()
                    .user(user)
                    .event(event)
                    .type(type)
                    .title(title)
                    .certificateCode(generateCode())
                    .issuedAt(Instant.now())
                    .build();
            certificateRepository.save(certificate);
            notifyIssued(user.getId(), title, event.getTitle());
            issued++;
        }
        return new CertificateBatchResponse(issued, skipped, reasons);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void autoIssueForCompletedEvent(Long eventId) {
        try {
            CertificateTemplate template = certificateTemplateRepository.findByEventId(eventId).orElse(null);
            if (template == null || !template.isAutoIssueOnComplete()) {
                return;
            }
            Event event = eventRepository.findById(eventId).orElse(null);
            if (event == null) {
                return;
            }
            CertificateBatchResponse result = generateInternal(
                    event, template, template.getAutoIssueScope(), template.getAutoIssueType());
            log.info("Auto-issued {} certificate(s) for completed event {} ({} skipped)",
                    result.issued(), eventId, result.skipped());
        } catch (Exception ex) {
            // Never let auto-issue roll back the status change that triggered it.
            log.error("Auto-issue of certificates failed for event {}: {}", eventId, ex.getMessage());
        }
    }

    /** Shared issuing loop used by both manual bulk generation and auto-issue. */
    private CertificateBatchResponse generateInternal(Event event, CertificateTemplate template,
                                                      CertificateRecipientScope scope, CertificateType type) {
        List<User> recipients = distinctRecipients(recipientsFor(event, scope));

        boolean gatePayment = template != null && template.isRequirePayment() && event.isPaidEvent();
        Set<Long> paidUserIds = gatePayment
                ? paymentRepository.findByEventIdAndStatus(event.getId(), PaymentStatus.SUCCESS).stream()
                        .filter(p -> p.getUser() != null)
                        .map(p -> p.getUser().getId())
                        .collect(Collectors.toSet())
                : Set.of();

        String title = resolveTitle(null, template, type);

        int issued = 0;
        int skipped = 0;
        List<String> reasons = new ArrayList<>();

        for (User user : recipients) {
            if (certificateRepository.existsByUserIdAndEventIdAndType(user.getId(), event.getId(), type)) {
                skipped++;
                continue; // already has one — silent skip
            }
            if (gatePayment && !paidUserIds.contains(user.getId())) {
                skipped++;
                reasons.add(user.getFullName() + " — payment not completed");
                continue;
            }
            Certificate certificate = Certificate.builder()
                    .user(user)
                    .event(event)
                    .type(type)
                    .title(title)
                    .certificateCode(generateCode())
                    .issuedAt(Instant.now())
                    .build();
            certificateRepository.save(certificate);
            notifyIssued(user.getId(), title, event.getTitle());
            issued++;
        }
        return new CertificateBatchResponse(issued, skipped, reasons);
    }

    private List<User> recipientsFor(Event event, CertificateRecipientScope scope) {
        if (scope == CertificateRecipientScope.ATTENDED) {
            return attendanceRepository.findByEventId(event.getId()).stream()
                    .map(a -> a.getUser())
                    .filter(u -> u != null)
                    .toList();
        }
        // REGISTERED: everyone with an active (non-cancelled, non-waitlisted) registration.
        return registrationRepository.findByEventId(event.getId()).stream()
                .filter(r -> r.getStatus() != RegistrationStatus.CANCELLED
                        && r.getStatus() != RegistrationStatus.WAITLISTED)
                .map(Registration::getUser)
                .filter(u -> u != null)
                .toList();
    }

    private static List<User> distinctRecipients(List<User> users) {
        Map<Long, User> byId = new LinkedHashMap<>();
        for (User u : users) {
            byId.putIfAbsent(u.getId(), u);
        }
        return new ArrayList<>(byId.values());
    }

    // ---- delivery ----

    @Override
    @Transactional(readOnly = true)
    public byte[] renderEventZip(Long actingUserId, Long eventId) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateTemplate template = resolveTemplate(event);
        List<Certificate> certificates = certificateRepository.findByEventId(eventId).stream()
                .filter(c -> !c.isRevoked())
                .toList();
        if (certificates.isEmpty()) {
            throw new BadRequestException("No certificates have been issued for this event yet.");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Set<String> usedNames = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Certificate certificate : certificates) {
                byte[] pdf = buildPdf(certificate, template);
                zip.putNextEntry(new ZipEntry(uniqueFileName(certificate, usedNames)));
                zip.write(pdf);
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to build certificate archive", ex);
        }
        return out.toByteArray();
    }

    @Override
    @Transactional(readOnly = true)
    public int emailEventCertificates(Long actingUserId, Long eventId) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateTemplate template = resolveTemplate(event);
        List<Certificate> certificates = certificateRepository.findByEventId(eventId).stream()
                .filter(c -> !c.isRevoked() && c.getUser() != null && StringUtils.hasText(c.getUser().getEmail()))
                .toList();

        int dispatched = 0;
        for (Certificate certificate : certificates) {
            byte[] pdf = buildPdf(certificate, template);
            String subject = certificate.getTitle() + " — " + event.getTitle();
            String body = "Hello " + certificate.getUser().getFullName() + ",\n\n"
                    + "Congratulations! Your certificate for \"" + event.getTitle() + "\" is attached as a PDF.\n\n"
                    + "You can verify or re-download it any time from your CampusConnect account.\n\n"
                    + "— CampusConnect";
            emailService.sendWithAttachment(certificate.getUser().getEmail(), subject, body,
                    baseFileName(certificate) + ".pdf", pdf, "application/pdf");
            dispatched++;
        }
        return dispatched;
    }

    // ---- admin template library ----

    @Override
    @Transactional(readOnly = true)
    public List<CertificateTemplateResponse> listLibraryTemplates(Long actingUserId) {
        requirePlatformAdmin(actingUserId);
        return certificateTemplateRepository.findByEventIsNullOrderByNameAsc().stream()
                .map(CertificateTemplateMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public CertificateTemplateResponse createLibraryTemplate(Long actingUserId, CertificateTemplateRequest request) {
        requirePlatformAdmin(actingUserId);
        CertificateTemplate template = newTemplate();
        CertificateTemplateMapper.apply(request, template);
        template.setEvent(null); // library templates are never bound to an event
        template = certificateTemplateRepository.save(template);
        return CertificateTemplateMapper.toResponse(template);
    }

    @Override
    @Transactional
    public CertificateTemplateResponse updateLibraryTemplate(Long actingUserId, Long templateId,
                                                             CertificateTemplateRequest request) {
        requirePlatformAdmin(actingUserId);
        CertificateTemplate template = certificateTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("CertificateTemplate", "id", templateId));
        if (template.getEvent() != null) {
            throw new BadRequestException("This template is bound to an event and is not a library template.");
        }
        CertificateTemplateMapper.apply(request, template);
        template.setEvent(null);
        template = certificateTemplateRepository.save(template);
        return CertificateTemplateMapper.toResponse(template);
    }

    @Override
    @Transactional
    public void deleteLibraryTemplate(Long actingUserId, Long templateId) {
        requirePlatformAdmin(actingUserId);
        CertificateTemplate template = certificateTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("CertificateTemplate", "id", templateId));
        if (template.getEvent() != null) {
            throw new BadRequestException("This template is bound to an event and cannot be deleted from the library.");
        }
        certificateTemplateRepository.delete(template);
    }

    @Override
    @Transactional
    public CertificateTemplateResponse applyLibraryTemplate(Long actingUserId, Long eventId, Long templateId) {
        Event event = findEvent(eventId);
        clubAccess.requireAdminOrCoordinator(event.getClub().getId(), actingUserId);

        CertificateTemplate library = certificateTemplateRepository.findById(templateId)
                .orElseThrow(() -> new ResourceNotFoundException("CertificateTemplate", "id", templateId));
        if (library.getEvent() != null) {
            throw new BadRequestException("Only a library template can be applied to an event.");
        }

        CertificateTemplate target = certificateTemplateRepository.findByEventId(eventId)
                .orElseGet(CertificateServiceImpl::newTemplate);
        copyDesign(library, target);
        target.setEvent(event);
        target = certificateTemplateRepository.save(target);
        return CertificateTemplateMapper.toResponse(target);
    }

    // ---- PDF rendering ----

    private byte[] buildPdf(Certificate certificate, CertificateTemplate template) {
        Rectangle pageSize = PageSize.A4.rotate();
        Document document = new Document(pageSize, 48, 48, 48, 48);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            document.open();

            float width = pageSize.getWidth();
            float height = pageSize.getHeight();
            PdfContentByte cb = writer.getDirectContent();

            boolean backgroundDrawn = drawBackground(cb, template.getBackgroundImageUrl(), width, height);
            if (!backgroundDrawn) {
                drawDecorativeBorder(cb, width, height);
            }

            Color titleColor = colorOf(template.getTitleColor(), new Color(30, 58, 138));
            Color nameColor = colorOf(template.getNameColor(), new Color(15, 23, 42));
            Color bodyColor = colorOf(template.getBodyColor(), new Color(51, 65, 85));

            Font titleFont = new Font(Font.HELVETICA, template.getTitleFontSize(), Font.BOLD, titleColor);
            Font subheadingFont = new Font(Font.HELVETICA, 13, Font.NORMAL, new Color(71, 85, 105));
            Font nameFont = new Font(Font.HELVETICA, template.getNameFontSize(), Font.BOLD, nameColor);
            Font bodyFont = new Font(Font.HELVETICA, template.getBodyFontSize(), Font.NORMAL, bodyColor);
            Font eventFont = new Font(Font.HELVETICA, template.getEventFontSize(), Font.BOLDITALIC, titleColor);
            Font small = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(100, 116, 139));

            if (template.isShowTitle()) {
                String title = StringUtils.hasText(template.getTitleText())
                        ? template.getTitleText() : certificate.getTitle();
                drawCentered(cb, title, titleFont, width, yOf(height, template.getTitleY()));
            }
            if (template.isShowPresentedTo() && StringUtils.hasText(template.getPresentedToText())) {
                drawCentered(cb, template.getPresentedToText(), subheadingFont, width,
                        yOf(height, template.getPresentedToY()));
            }

            String recipient = certificate.getUser() != null ? certificate.getUser().getFullName() : "Participant";
            drawCentered(cb, recipient, nameFont, width, yOf(height, template.getNameY()));

            if (template.isShowBody() && StringUtils.hasText(template.getBodyText())) {
                drawCentered(cb, template.getBodyText(), bodyFont, width, yOf(height, template.getBodyY()));
            }
            if (template.isShowEvent()) {
                String eventTitle = certificate.getEvent() != null ? certificate.getEvent().getTitle() : "the event";
                drawCentered(cb, eventTitle, eventFont, width, yOf(height, template.getEventY()));
            }
            if (template.isShowDate() && certificate.getIssuedAt() != null) {
                drawCentered(cb, "Issued on " + DATE_FMT.format(certificate.getIssuedAt()), bodyFont, width,
                        yOf(height, template.getDateY()));
            }
            if (template.isShowQr()) {
                drawQr(cb, certificate.getCertificateCode(), width, yOf(height, template.getQrY()), small);
            }

            document.close();
            return out.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to render certificate PDF", ex);
        }
    }

    private boolean drawBackground(PdfContentByte cb, String url, float width, float height) {
        if (!StringUtils.hasText(url)) {
            return false;
        }
        try {
            Image background = loadImage(url);
            background.scaleAbsolute(width, height);
            background.setAbsolutePosition(0, 0);
            cb.addImage(background);
            return true;
        } catch (Exception ex) {
            // A broken/unreachable background must never break issuing — fall back to the built-in design.
            log.warn("Could not render certificate background image: {}", ex.getMessage());
            return false;
        }
    }

    private static void drawDecorativeBorder(PdfContentByte cb, float width, float height) {
        cb.setColorStroke(new Color(37, 99, 235));
        cb.setLineWidth(3f);
        cb.rectangle(28, 28, width - 56, height - 56);
        cb.stroke();
        cb.setColorStroke(new Color(147, 197, 253));
        cb.setLineWidth(1f);
        cb.rectangle(38, 38, width - 76, height - 76);
        cb.stroke();
    }

    private void drawQr(PdfContentByte cb, String code, float width, float centerY, Font caption) throws Exception {
        String verifyUrl = verifyUrl(code);
        byte[] qr = qrCodeService.generatePng(verifyUrl, QR_SIZE);
        Image qrImage = Image.getInstance(qr);
        qrImage.scaleAbsolute(QR_DISPLAY, QR_DISPLAY);
        qrImage.setAbsolutePosition(width / 2f - QR_DISPLAY / 2f, centerY - QR_DISPLAY / 2f);
        cb.addImage(qrImage);

        float captionY = centerY - QR_DISPLAY / 2f - 12f;
        drawCentered(cb, "Scan to verify  •  " + verifyUrl, caption, width, captionY);
        drawCentered(cb, "Certificate code: " + code, caption, width, captionY - 11f);
    }

    private static void drawCentered(PdfContentByte cb, String text, Font font, float width, float y) {
        if (text == null) {
            return;
        }
        ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, new Phrase(text, font), width / 2f, y, 0);
    }

    /** Converts a normalized top-down fraction (0=top, 1=bottom) to a PDF y-coordinate (origin bottom-left). */
    private static float yOf(float height, double normalized) {
        double clamped = Math.max(0.0, Math.min(1.0, normalized));
        return (float) (height * (1.0 - clamped));
    }

    private Image loadImage(String url) throws Exception {
        String trimmed = url.trim();
        // Inline data URL: decode the base64 payload directly.
        if (trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = trimmed.indexOf(',');
            if (comma < 0) {
                throw new IOException("Malformed data URL");
            }
            byte[] bytes = Base64.getDecoder().decode(trimmed.substring(comma + 1)
                    .replaceAll("\\s", ""));
            return Image.getInstance(bytes);
        }
        // Locally-stored upload: the URL is a relative API path (e.g. "/api/files/<key>") produced by
        // LocalStorageService. Read the bytes straight from disk instead of issuing an HTTP self-call
        // (a relative URL is not absolute, so URI.toURL() would fail here anyway).
        byte[] local = loadLocalUpload(trimmed);
        if (local != null) {
            return Image.getInstance(local);
        }
        // Absolute URL (S3/CDN object or any external image).
        return Image.getInstance(URI.create(trimmed).toURL());
    }

    /**
     * Resolve a relative local-storage URL (as returned by {@link LocalStorageService}) to its bytes.
     * Returns {@code null} when the URL is not a local-storage path — an absolute http(s)/S3 URL, or a
     * relative path outside the storage prefix — so the caller falls through to the absolute-URL branch.
     * Never issues a network call.
     */
    private byte[] loadLocalUpload(String url) {
        // Only relative paths belong to the local backend; absolute URLs are served by their own host.
        if (url.isEmpty() || url.charAt(0) != '/') {
            return null;
        }
        String prefix = localPublicBaseUrl.endsWith("/") ? localPublicBaseUrl : localPublicBaseUrl + "/";
        if (!url.startsWith(prefix)) {
            return null;
        }
        String key = url.substring(prefix.length());
        // Defensively strip any query string / fragment that might trail the key.
        int cut = key.indexOf('?');
        if (cut >= 0) {
            key = key.substring(0, cut);
        }
        cut = key.indexOf('#');
        if (cut >= 0) {
            key = key.substring(0, cut);
        }
        return localStorageService.load(key).orElse(null);
    }

    private static Color colorOf(String hex, Color fallback) {
        if (hex == null || !hex.matches("^#[0-9a-fA-F]{6}$")) {
            return fallback;
        }
        return new Color(
                Integer.parseInt(hex.substring(1, 3), 16),
                Integer.parseInt(hex.substring(3, 5), 16),
                Integer.parseInt(hex.substring(5, 7), 16));
    }

    // ---- helpers ----

    private Event findEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", "id", eventId));
    }

    private void requirePlatformAdmin(Long actingUserId) {
        if (!clubAccess.isPlatformAdmin(actingUserId)) {
            throw new ForbiddenException("Only an administrator can manage the certificate template library.");
        }
    }

    /** The event's configured template, or a transient all-defaults template when none exists. */
    private CertificateTemplate resolveTemplate(Event event) {
        if (event == null) {
            return newTemplate();
        }
        return certificateTemplateRepository.findByEventId(event.getId())
                .orElseGet(CertificateServiceImpl::newTemplate);
    }

    /**
     * A fresh template carrying its default design. Built via the Lombok builder on purpose:
     * {@code new CertificateTemplate()} would leave {@code @Builder.Default} fields at their raw
     * type defaults (0 / false / null) rather than the intended values.
     */
    private static CertificateTemplate newTemplate() {
        return CertificateTemplate.builder().build();
    }

    private static String resolveTitle(String explicit, CertificateTemplate template, CertificateType type) {
        if (StringUtils.hasText(explicit)) {
            return explicit.trim();
        }
        if (template != null && StringUtils.hasText(template.getTitleText())) {
            return template.getTitleText().trim();
        }
        return defaultTitle(type);
    }

    private void notifyIssued(Long userId, String title, String eventTitle) {
        notificationService.notifyUser(userId, NotificationType.CERTIFICATE_ISSUED,
                "Certificate issued",
                "Your " + title + " for \"" + eventTitle + "\" is ready to download.",
                "/certificates");
    }

    /** Copies every design/behaviour field (but not identity or event binding) from one template to another. */
    private static void copyDesign(CertificateTemplate from, CertificateTemplate to) {
        to.setName(from.getName());
        to.setBackgroundImageUrl(from.getBackgroundImageUrl());
        to.setTitleText(from.getTitleText());
        to.setPresentedToText(from.getPresentedToText());
        to.setBodyText(from.getBodyText());
        to.setShowTitle(from.isShowTitle());
        to.setShowPresentedTo(from.isShowPresentedTo());
        to.setShowBody(from.isShowBody());
        to.setShowEvent(from.isShowEvent());
        to.setShowDate(from.isShowDate());
        to.setShowQr(from.isShowQr());
        to.setTitleY(from.getTitleY());
        to.setPresentedToY(from.getPresentedToY());
        to.setNameY(from.getNameY());
        to.setBodyY(from.getBodyY());
        to.setEventY(from.getEventY());
        to.setDateY(from.getDateY());
        to.setQrY(from.getQrY());
        to.setTitleFontSize(from.getTitleFontSize());
        to.setNameFontSize(from.getNameFontSize());
        to.setBodyFontSize(from.getBodyFontSize());
        to.setEventFontSize(from.getEventFontSize());
        to.setTitleColor(from.getTitleColor());
        to.setNameColor(from.getNameColor());
        to.setBodyColor(from.getBodyColor());
        to.setRequirePayment(from.isRequirePayment());
        to.setAutoIssueOnComplete(from.isAutoIssueOnComplete());
        to.setAutoIssueScope(from.getAutoIssueScope());
        to.setAutoIssueType(from.getAutoIssueType());
    }

    private static String baseFileName(Certificate certificate) {
        String person = certificate.getUser() != null ? certificate.getUser().getFullName() : "participant";
        String slug = person.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "participant";
        }
        return slug + "-" + certificate.getCertificateCode();
    }

    private static String uniqueFileName(Certificate certificate, Set<String> used) {
        String base = baseFileName(certificate);
        String candidate = base + ".pdf";
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "-" + suffix++ + ".pdf";
        }
        return candidate;
    }

    private String verifyUrl(String code) {
        String base = frontendBaseUrl.endsWith("/") ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1)
                : frontendBaseUrl;
        return base + "/verify-certificate/" + code;
    }

    private static String defaultTitle(CertificateType type) {
        return switch (type) {
            case WINNER -> "Certificate of Achievement";
            case MERIT -> "Certificate of Merit";
            case PARTICIPATION -> "Certificate of Participation";
        };
    }

    private String generateCode() {
        String code;
        do {
            code = (UUID.randomUUID().toString() + UUID.randomUUID().toString())
                    .replace("-", "").substring(0, 40).toUpperCase(Locale.ROOT);
        } while (certificateRepository.findByCertificateCode(code).isPresent());
        return code;
    }
}
