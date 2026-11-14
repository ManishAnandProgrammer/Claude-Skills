package com.example.app.user.controller;

import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PlatformImpersonationApi;
import com.example.app.user.dto.request.ImpersonationRequest;
import com.example.app.user.dto.response.TokenResponse;
import com.example.app.user.service.ImpersonationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Control plane: a platform token only (SecurityConfig's platform chain). */
@RestController
@RequestMapping("/api/{version}/platform/tenants/{tenantId}/impersonations")
@RequiredArgsConstructor
class PlatformImpersonationController implements PlatformImpersonationApi {

    private final ImpersonationService service;

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TokenResponse> impersonate(@PathVariable UUID tenantId, @RequestBody @Valid ImpersonationRequest request) {
        return service.impersonate(tenantId, request);
    }
}
