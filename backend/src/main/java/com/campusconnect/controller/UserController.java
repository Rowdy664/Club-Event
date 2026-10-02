package com.campusconnect.controller;

import com.campusconnect.common.ApiResponse;
import com.campusconnect.dto.request.ChangePasswordRequest;
import com.campusconnect.dto.request.UpdateProfileRequest;
import com.campusconnect.dto.response.UserResponse;
import com.campusconnect.entity.enums.Role;
import com.campusconnect.exception.ForbiddenException;
import com.campusconnect.security.UserPrincipal;
import com.campusconnect.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Users", description = "Current-user profile and account management")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "Get the authenticated user's profile")
    @GetMapping("/me")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        return ApiResponse.success(userService.getById(principal.getId()));
    }

    @Operation(summary = "Update the authenticated user's profile")
    @PutMapping("/me")
    public ApiResponse<UserResponse> updateProfile(@AuthenticationPrincipal UserPrincipal principal,
                                                   @Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success("Profile updated.", userService.updateProfile(principal.getId(), request));
    }

    @Operation(summary = "Change the authenticated user's password")
    @PostMapping("/me/change-password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal UserPrincipal principal,
                                            @Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(principal.getId(), request);
        return ApiResponse.message("Password changed successfully.");
    }

    @Operation(summary = "Get a user's profile by id (self or admin only)")
    @GetMapping("/{id}")
    public ApiResponse<UserResponse> getById(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long id) {
        if (!id.equals(principal.getId()) && principal.getRole() != Role.ADMIN) {
            throw new ForbiddenException("You can only view your own profile.");
        }
        return ApiResponse.success(userService.getById(id));
    }
}
