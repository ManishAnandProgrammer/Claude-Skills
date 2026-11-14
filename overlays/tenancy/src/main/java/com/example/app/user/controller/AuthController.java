package com.example.app.user.controller;

import com.example.app.common.apiversion.RequestApiVersion;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.AuthApi;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.request.RefreshTokenRequest;
import com.example.app.user.dto.request.SignupRequest;
import com.example.app.user.dto.request.SwitchTenantRequest;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.service.AuthService;
import com.example.app.user.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/{version}/auth")
@RequiredArgsConstructor
class AuthController implements AuthApi {

    /** In the version the client called: a v1+ signup serving a later version must not point back to v1. */
    private static final String OWN_ACCOUNT = "/api/{version}/users/me";

    private final UserService userService;
    private final AuthService authService;

    @Override
    @PostMapping(path = "/signup", version = "v1+")
    public ResponseEntity<ObjectWrapper<UserResponse>> signup(@RequestBody @Valid SignupRequest request, HttpServletRequest http) {
        URI ownAccount = UriComponentsBuilder.fromPath(OWN_ACCOUNT)
                .buildAndExpand(RequestApiVersion.of(http).orElseThrow())
                .toUri();
        return ResponseEntity.created(ownAccount).body(userService.signup(request, http.getRemoteAddr()));
    }

    @Override
    @PostMapping(path = "/login", version = "v1+")
    public ObjectWrapper<TokenResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    @Override
    @PostMapping(path = "/refresh-token", version = "v1+")
    public ObjectWrapper<TokenResponse> refresh(@RequestBody @Valid RefreshTokenRequest request, HttpServletRequest http) {
        return authService.refresh(request, http.getRemoteAddr());
    }

    @Override
    @PostMapping(path = "/switch-tenant", version = "v1+")
    public ObjectWrapper<TokenResponse> switchTenant(@RequestBody @Valid SwitchTenantRequest request, HttpServletRequest http) {
        return authService.switchTenant(request, http.getRemoteAddr());
    }

    @Override
    @PostMapping(path = "/logout", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid RefreshTokenRequest request, HttpServletRequest http) {
        authService.logout(request, http.getRemoteAddr());
    }
}
