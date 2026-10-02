package com.campusconnect.service;

import com.campusconnect.dto.request.ForgotPasswordRequest;
import com.campusconnect.dto.request.LoginOtpRequest;
import com.campusconnect.dto.request.LoginRequest;
import com.campusconnect.dto.request.RegisterRequest;
import com.campusconnect.dto.request.ResetPasswordRequest;
import com.campusconnect.dto.request.VerifyOtpRequest;
import com.campusconnect.dto.response.AuthResponse;

public interface AuthService {

    AuthResponse register(RegisterRequest request);

    /**
     * Password step of login. {@code clientKey} scopes the brute-force rate limit
     * (typically client IP). Returns issued tokens, or — when the account has 2FA
     * enabled — a challenge that must be completed via {@link #verifyOtp}.
     */
    AuthResponse login(LoginRequest request, String clientKey);

    /**
     * Passwordless login: issue a one-time code to the account matching {@code identifier}
     * (email or phone), delivered by email and — when configured — WhatsApp. Always returns a
     * challenge (completed via {@link #verifyOtp}); it never reveals whether an account matched.
     * {@code clientKey} scopes the abuse rate limit (typically client IP).
     */
    AuthResponse requestLoginOtp(LoginOtpRequest request, String clientKey);

    /** Completes a two-factor login by validating the emailed OTP against the challenge. */
    AuthResponse verifyOtp(VerifyOtpRequest request, String clientKey);

    AuthResponse refresh(String refreshToken);

    void verifyEmail(String token);

    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    /** Enables or disables email-OTP two-factor auth for the given user. */
    void setTwoFactor(Long userId, boolean enabled);
}
