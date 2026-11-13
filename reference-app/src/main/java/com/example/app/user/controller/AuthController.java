package com.example.app.user.controller;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.AuthApi;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.request.RefreshTokenRequest;
import com.example.app.user.dto.request.SignupRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
class AuthController implements AuthApi {

    private static final URI OWN_ACCOUNT = URI.create("/api/v1/users/me");

    private final UserService userService;
    private final AuthService authService;

    @Override
    @PostMapping("/api/v1/auth/signup")
    public ResponseEntity<ObjectWrapper<UserResponse>> signup(@RequestBody @Valid SignupRequest request, HttpServletRequest http) {
        return ResponseEntity.created(OWN_ACCOUNT).body(userService.signup(request, http.getRemoteAddr()));
    }

    @Override
    @PostMapping("/api/v1/auth/login")
    public ObjectWrapper<TokenResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return authService.login(request, http.getRemoteAddr());
    }

    @Override
    @PostMapping("/api/v1/auth/refresh-token")
    public ObjectWrapper<TokenResponse> refresh(@RequestBody @Valid RefreshTokenRequest request, HttpServletRequest http) {
        return authService.refresh(request, http.getRemoteAddr());
    }

    @Override
    @PostMapping("/api/v1/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody @Valid RefreshTokenRequest request, HttpServletRequest http) {
        authService.logout(request, http.getRemoteAddr());
    }
}
