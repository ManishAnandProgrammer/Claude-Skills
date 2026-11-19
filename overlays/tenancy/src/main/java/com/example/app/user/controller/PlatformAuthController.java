package com.example.app.user.controller;

import com.example.app.common.security.Public;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PlatformAuthApi;
import com.example.app.user.dto.request.LoginRequest;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.service.PlatformAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/platform/auth")
@RequiredArgsConstructor
class PlatformAuthController implements PlatformAuthApi {

    private final PlatformAuthService service;

    @Override
    @PostMapping(path = "/login", version = "v1+")
    @Public
    public ObjectWrapper<TokenResponse> login(@RequestBody @Valid LoginRequest request, HttpServletRequest http) {
        return service.login(request, http.getRemoteAddr());
    }
}
