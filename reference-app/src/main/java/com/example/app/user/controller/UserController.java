package com.example.app.user.controller;

// tenancy:begin
// tenancy:else
import com.example.app.common.security.AdminOnly;
// tenancy:end
import com.example.app.common.security.CurrentUser;
// tenancy:begin
// tenancy:else
import com.example.app.common.wrapper.ListWrapper;
// tenancy:end
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.UserApi;
import com.example.app.user.dto.request.PasswordChangeRequest;
// tenancy:begin
// tenancy:else
import com.example.app.user.dto.request.UserFilter;
import com.example.app.user.dto.request.UserStatusRequest;
// tenancy:end
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.dto.response.UserResponse;
// tenancy:begin
// tenancy:else
import com.example.app.user.service.AccountStatusService;
// tenancy:end
import com.example.app.user.service.AuthService;
import com.example.app.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
// tenancy:begin
// tenancy:else
import org.springdoc.core.annotations.ParameterObject;
// tenancy:end
// session-version:begin
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
// session-version:end
import org.springframework.web.bind.annotation.GetMapping;
// tenancy:begin
// tenancy:else
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
// tenancy:end
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
// session-version:begin
import org.springframework.web.bind.annotation.ResponseStatus;
// session-version:end
import org.springframework.web.bind.annotation.RestController;
// tenancy:begin
// tenancy:else

import java.util.UUID;
// tenancy:end

/**
 * Any signed-in user, for their own account only (the id comes from the token, never the path).
 *   <!-- tenancy:begin -->
 * Accounts are suspended by platform staff (PlatformUserController), never through this tenant-plane API.
 *   <!-- tenancy:else -->
 * The one exception, an ADMIN's: finding another account by its address, and suspending or reactivating it.
 *   <!-- tenancy:end -->
 */
@RestController
@RequestMapping("/api/{version}/users")
@RequiredArgsConstructor
class UserController implements UserApi {

    private final UserService service;
    private final CurrentUser currentUser;
    private final AuthService authService;
    // tenancy:begin
    // tenancy:else
    private final AccountStatusService accountStatus;
    // tenancy:end

    @Override
    @GetMapping(path = "/me", version = "v1+")
    public ObjectWrapper<UserResponse> me() {
        return service.me(currentUser.id());
    }

    @Override
    @PostMapping(path = "/me/password", version = "v1+")
    public ObjectWrapper<TokenResponse> changePassword(@RequestBody @Valid PasswordChangeRequest request, HttpServletRequest http) {
        return authService.changePassword(currentUser.id(), request, http.getRemoteAddr());
    }
    // session-version:begin

    @Override
    @DeleteMapping(path = "/me/sessions", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endAllSessions() {
        authService.logoutEverywhere(currentUser.id());
    }
    // session-version:end
    // tenancy:begin
    // tenancy:else

    @Override
    @AdminOnly
    @GetMapping(version = "v1+")
    public ListWrapper<UserResponse> lookUp(@Valid @ParameterObject UserFilter filter) {
        return service.lookUp(filter);
    }

    @Override
    @AdminOnly
    @PatchMapping(path = "/{id}/status", version = "v1+")
    public ObjectWrapper<UserResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid UserStatusRequest request) {
        return accountStatus.changeStatus(currentUser.id(), id, request);
    }
    // tenancy:end
}
