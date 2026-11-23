package com.example.app.user.controller;

// tenancy:begin
// tenancy:else
import com.example.app.common.dropdown.DropdownFilter;
import com.example.app.common.dropdown.DropdownFor;
import com.example.app.common.dropdown.DropdownItem;
// tenancy:end
import com.example.app.common.security.CurrentUser;
// tenancy:begin
// tenancy:else
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
// tenancy:end
import com.example.app.common.security.SignedIn;
// tenancy:begin
// tenancy:else
import com.example.app.common.wrapper.BoundedListWrapper;
import com.example.app.common.wrapper.CursorWrapper;
// tenancy:end
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.UserApi;
import com.example.app.user.dto.request.PasswordChangeRequest;
// tenancy:begin
// tenancy:else
import com.example.app.user.dto.request.UserFilter;
import com.example.app.user.dto.request.UserStatusRequest;
// tenancy:end
import com.example.app.user.dto.response.AccessResponse;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.dto.response.UserResponse;
// tenancy:begin
// tenancy:else
import com.example.app.user.service.AccountStatusService;
// tenancy:end
import com.example.app.user.service.AuthService;
// tenancy:begin
// tenancy:else
import com.example.app.user.service.NamedPartyService;
// tenancy:end
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
// tenancy:begin
// tenancy:else
import org.springframework.web.bind.annotation.RequestParam;
// tenancy:end
// session-version:begin
import org.springframework.web.bind.annotation.ResponseStatus;
// session-version:end
import org.springframework.web.bind.annotation.RestController;
// tenancy:begin
// tenancy:else

import java.util.UUID;
// tenancy:end

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
    private final NamedPartyService namedParties;
    // tenancy:end

    @Override
    @GetMapping(path = "/me", version = "v1+")
    @SignedIn
    public ObjectWrapper<UserResponse> me() {
        return service.me(currentUser.id());
    }

    @Override
    @GetMapping(path = "/me/access", version = "v1+")
    @SignedIn
    public ObjectWrapper<AccessResponse> access() {
        return service.access(currentUser.permissions());
    }

    @Override
    @PostMapping(path = "/me/password", version = "v1+")
    @SignedIn
    public ObjectWrapper<TokenResponse> changePassword(@RequestBody @Valid PasswordChangeRequest request, HttpServletRequest http) {
        return authService.changePassword(currentUser.id(), request, http.getRemoteAddr());
    }
    // session-version:begin

    @Override
    @DeleteMapping(path = "/me/sessions", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @SignedIn
    public void endAllSessions() {
        authService.endAllSessions(currentUser.id());
    }
    // session-version:end
    // tenancy:begin
    // tenancy:else

    @Override
    @GetMapping(version = "v1+")
    @Requires(Permission.ACCOUNT_READ)
    public BoundedListWrapper<UserResponse> lookUp(@Valid @ParameterObject UserFilter filter) {
        return accountStatus.lookUp(filter);
    }

    @Override
    @PatchMapping(path = "/{id}/status", version = "v1+")
    @Requires(Permission.ACCOUNT_STATUS_CHANGE)
    public ObjectWrapper<UserResponse> changeStatus(@PathVariable UUID id, @RequestBody @Valid UserStatusRequest request) {
        return accountStatus.changeStatus(currentUser.id(), id, request);
    }

    @Override
    @GetMapping(path = "/dropdown", version = "v1+")
    @SignedIn
    public CursorWrapper<DropdownItem> dropdown(@RequestParam(DropdownFor.PARAMETER) Permission action,
                                                @Valid DropdownFilter filter) {
        return namedParties.dropdown(action, filter);
    }
    // tenancy:end
}
