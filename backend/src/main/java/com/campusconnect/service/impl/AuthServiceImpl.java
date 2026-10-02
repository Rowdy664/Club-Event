package com.campusconnect.service.impl;

import com.campusconnect.dto.request.ForgotPasswordRequest;
import com.campusconnect.dto.request.LoginOtpRequest;
import com.campusconnect.dto.request.LoginRequest;
import com.campusconnect.dto.request.RegisterRequest;
import com.campusconnect.dto.request.ResetPasswordRequest;
import com.campusconnect.dto.request.VerifyOtpRequest;
import com.campusconnect.dto.response.AuthResponse;
import com.campusconnect.entity.NotificationPreference;
import com.campusconnect.entity.User;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.exception.BadRequestException;
import com.campusconnect.exception.ConflictException;
import com.campusconnect.exception.ResourceNotFoundException;
import com.campusconnect.exception.TooManyRequestsException;
import com.campusconnect.mapper.UserMapper;
import com.campusconnect.repository.NotificationPreferenceRepository;
import com.campusconnect.repository.UserRepository;
import com.campusconnect.security.JwtService;
import com.campusconnect.security.LoginRateLimiter;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.AuthService;
import com.campusconnect.service.EmailService;
import com.campusconnect.service.whatsapp.WhatsAppResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    /** Max wrong OTP guesses before the active challenge is invalidated. */
    private static final int MAX_OTP_ATTEMPTS = 5;
    /** OTP lifetime in minutes. */
    private static final int OTP_TTL_MINUTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final NotificationPreferenceRepository notificationPreferenceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;
    private final EmailService emailService;
    private final LoginRateLimiter loginRateLimiter;
    private final WhatsAppResolver whatsAppResolver;

    @Value("${app.frontend.base-url}")
    private String frontendBaseUrl;

    @Value("${app.college-email-domain:}")
    private String collegeEmailDomain;

    @Override
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim().toLowerCase();

        if (collegeEmailDomain != null && !collegeEmailDomain.isBlank()
                && !email.endsWith("@" + collegeEmailDomain.toLowerCase())) {
            throw new BadRequestException("Registration is restricted to @" + collegeEmailDomain + " email addresses.");
        }
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("An account with this email already exists.");
        }

        String verificationToken = UUID.randomUUID().toString();
        User user = User.builder()
                .fullName(request.fullName().trim())
                .email(email)
                .password(passwordEncoder.encode(request.password()))
                .role(Role.STUDENT)
                .studentId(request.studentId())
                .department(request.department())
                .phone(request.phone())
                .enabled(true)
                .emailVerified(false)
                .verificationToken(verificationToken)
                .build();
        user = userRepository.save(user);

        // Every account starts with an all-on preference row (and its own unsubscribe token),
        // so notification delivery never has to create one inside a business transaction.
        notificationPreferenceRepository.save(NotificationPreference.defaultsFor(user));

        emailService.send(email, "Verify your CampusConnect account",
                "Welcome to CampusConnect, %s!\n\nVerify your email: %s/verify-email?token=%s"
                        .formatted(user.getFullName(), frontendBaseUrl, verificationToken));

        return buildTokens(user);
    }

    @Override
    @Transactional
    public AuthResponse login(LoginRequest request, String clientKey) {
        String email = request.email().trim().toLowerCase();

        // Brute-force guard: block before touching the AuthenticationManager so
        // repeated password guesses can't be used as an oracle once locked.
        String rateKey = rateKey(clientKey, email);
        long retryAfter = loginRateLimiter.retryAfterSeconds(rateKey);
        if (retryAfter > 0) {
            throw new TooManyRequestsException(
                    "Too many login attempts. Try again in " + retryAfter + " seconds.");
        }

        try {
            // Throws BadCredentials/Disabled on failure -> handled globally as 401.
            authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, request.password()));
        } catch (RuntimeException ex) {
            loginRateLimiter.recordFailure(rateKey);
            throw ex;
        }

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));

        // Password verified — clear the failure counter for this key.
        loginRateLimiter.reset(rateKey);

        if (user.isTwoFactorEnabled()) {
            return startTwoFactorChallenge(user);
        }
        return buildTokens(user);
    }

    @Override
    @Transactional
    public AuthResponse requestLoginOtp(LoginOtpRequest request, String clientKey) {
        String identifier = request.identifier().trim();

        // Abuse guard: cap how many codes can be requested per client+identifier window, so this
        // can't be used to bomb someone's inbox / WhatsApp. Uses the same limiter as password login.
        String rateKey = rateKey(clientKey, identifier.toLowerCase());
        long retryAfter = loginRateLimiter.retryAfterSeconds(rateKey);
        if (retryAfter > 0) {
            throw new TooManyRequestsException(
                    "Too many code requests. Try again in " + retryAfter + " seconds.");
        }
        loginRateLimiter.recordFailure(rateKey);

        // An identifier with '@' is treated as an email (unique); otherwise as a phone number.
        Optional<User> match = identifier.contains("@")
                ? userRepository.findByEmail(identifier.toLowerCase())
                : userRepository.findFirstByPhoneOrderByIdAsc(identifier);

        if (match.isPresent() && match.get().isEnabled()) {
            return issueOtpChallenge(match.get(), "Your CampusConnect sign-in code");
        }
        // Non-enumerating: hand back a challenge token even when nothing matched, so response
        // shape/timing can't reveal which accounts exist. An unbound token just fails at verify.
        return AuthResponse.challenge(UUID.randomUUID().toString());
    }

    @Override
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request, String clientKey) {
        String challengeKey = "otp:" + request.challengeToken();
        long retryAfter = loginRateLimiter.retryAfterSeconds(challengeKey);
        if (retryAfter > 0) {
            throw new TooManyRequestsException(
                    "Too many verification attempts. Try again in " + retryAfter + " seconds.");
        }

        User user = userRepository.findByOtpChallengeToken(request.challengeToken())
                .orElseThrow(() -> new BadRequestException("This verification session is invalid or has expired."));

        if (user.getOtpExpiry() == null || user.getOtpExpiry().isBefore(Instant.now())) {
            clearOtp(user);
            userRepository.save(user);
            throw new BadRequestException("Your verification code has expired. Please sign in again.");
        }

        boolean matches = user.getOtpCodeHash() != null
                && passwordEncoder.matches(request.code(), user.getOtpCodeHash());
        if (!matches) {
            user.setOtpAttempts(user.getOtpAttempts() + 1);
            loginRateLimiter.recordFailure(challengeKey);
            if (user.getOtpAttempts() >= MAX_OTP_ATTEMPTS) {
                clearOtp(user);
                userRepository.save(user);
                throw new BadRequestException("Too many incorrect codes. Please sign in again.");
            }
            userRepository.save(user);
            throw new BadRequestException("That verification code is incorrect.");
        }

        // Success — burn the challenge so the code can't be replayed.
        clearOtp(user);
        userRepository.save(user);
        loginRateLimiter.reset(challengeKey);
        return buildTokens(user);
    }

    @Override
    @Transactional
    public void setTwoFactor(Long userId, boolean enabled) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setTwoFactorEnabled(enabled);
        // Turning 2FA off (or on) invalidates any in-flight challenge.
        clearOtp(user);
        userRepository.save(user);
    }

    /**
     * Post-password 2FA step. Delegates to the shared challenge issuer.
     */
    private AuthResponse startTwoFactorChallenge(User user) {
        return issueOtpChallenge(user, "Your CampusConnect sign-in code");
    }

    /**
     * Generates a fresh 6-digit OTP, stores only its hash + expiry + a new opaque challenge token,
     * and delivers the code by email <em>and</em> (best-effort) WhatsApp. Returns the challenge the
     * client completes via {@link #verifyOtp}. Shared by 2FA login and passwordless login so there
     * is a single OTP code path.
     *
     * <p>WhatsApp delivery is fail-soft (the resolver's provider never throws and degrades to a log
     * line until Twilio credentials are configured), so a messaging outage never blocks sign-in.
     */
    private AuthResponse issueOtpChallenge(User user, String emailSubject) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        String challengeToken = UUID.randomUUID().toString();
        user.setOtpCodeHash(passwordEncoder.encode(code));
        user.setOtpExpiry(Instant.now().plus(OTP_TTL_MINUTES, ChronoUnit.MINUTES));
        user.setOtpAttempts(0);
        user.setOtpChallengeToken(challengeToken);
        userRepository.save(user);

        emailService.send(user.getEmail(), emailSubject,
                ("Hi %s,\n\nYour CampusConnect verification code is: %s\n\n"
                        + "It expires in %d minutes. If you didn't try to sign in, you can ignore this "
                        + "message and consider changing your password.")
                        .formatted(user.getFullName(), code, OTP_TTL_MINUTES));

        whatsAppResolver.resolve().sendText(user.getPhone(),
                "Your CampusConnect sign-in code is " + code
                        + ". It expires in " + OTP_TTL_MINUTES + " minutes.");

        return AuthResponse.challenge(challengeToken);
    }

    private void clearOtp(User user) {
        user.setOtpCodeHash(null);
        user.setOtpExpiry(null);
        user.setOtpAttempts(0);
        user.setOtpChallengeToken(null);
    }

    private static String rateKey(String clientKey, String email) {
        return (clientKey == null ? "?" : clientKey) + ":" + email;
    }

    @Override
    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        String email;
        try {
            email = jwtService.extractUsername(refreshToken);
        } catch (Exception ex) {
            throw new BadRequestException("Invalid refresh token.");
        }
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadRequestException("Invalid refresh token."));
        if (!jwtService.isTokenValid(refreshToken, UserPrincipal.from(user))) {
            throw new BadRequestException("Refresh token is expired or invalid.");
        }
        return buildTokens(user);
    }

    @Override
    @Transactional
    public void verifyEmail(String token) {
        User user = userRepository.findByVerificationToken(token)
                .orElseThrow(() -> new BadRequestException("Invalid or expired verification token."));
        user.setEmailVerified(true);
        user.setVerificationToken(null);
        userRepository.save(user);
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        String email = request.email().trim().toLowerCase();
        // Do not reveal whether the email exists.
        userRepository.findByEmail(email).ifPresent(user -> {
            String resetToken = UUID.randomUUID().toString();
            user.setResetToken(resetToken);
            user.setResetTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
            userRepository.save(user);
            emailService.send(email, "Reset your CampusConnect password",
                    "Reset your password (valid for 1 hour): %s/reset-password?token=%s"
                            .formatted(frontendBaseUrl, resetToken));
        });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        User user = userRepository.findByResetToken(request.token())
                .orElseThrow(() -> new BadRequestException("Invalid or expired reset token."));
        if (user.getResetTokenExpiry() == null || user.getResetTokenExpiry().isBefore(Instant.now())) {
            throw new BadRequestException("Reset token has expired. Please request a new one.");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        user.setResetToken(null);
        user.setResetTokenExpiry(null);
        userRepository.save(user);
    }

    private AuthResponse buildTokens(User user) {
        UserPrincipal principal = UserPrincipal.from(user);
        String accessToken = jwtService.generateAccessToken(principal);
        String refreshToken = jwtService.generateRefreshToken(principal);
        return AuthResponse.tokens(accessToken, refreshToken,
                jwtService.getAccessExpirationMs() / 1000, UserMapper.toResponse(user));
    }
}
