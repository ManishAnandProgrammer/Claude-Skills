package com.example.app.user.controller;

import com.example.app.user.controller.api.TenantRegistrationApi;
import com.example.app.user.dto.request.TenantRegistrationRequest;
import com.example.app.user.service.TenantRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Under /auth, which is public: registering is signing up an organisation. */
@RestController
@RequestMapping("/api/{version}/auth/tenant-registrations")
@RequiredArgsConstructor
class TenantRegistrationController implements TenantRegistrationApi {

    private final TenantRegistrationService service;

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void register(@RequestBody @Valid TenantRegistrationRequest request, HttpServletRequest http) {
        service.register(request, http.getRemoteAddr());
    }
}
