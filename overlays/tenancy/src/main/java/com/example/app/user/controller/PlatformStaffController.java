package com.example.app.user.controller;

import com.example.app.common.security.SignedIn;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PlatformStaffApi;
import com.example.app.user.dto.response.PlatformStaffResponse;
import com.example.app.user.service.PlatformStaffService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/platform/staff")
@RequiredArgsConstructor
class PlatformStaffController implements PlatformStaffApi {

    private final PlatformStaffService service;

    @Override
    @PostMapping(path = "/{id}/deactivation", version = "v1+")
    @SignedIn
    public ObjectWrapper<PlatformStaffResponse> deactivate(@PathVariable UUID id) {
        return service.deactivate(id);
    }
}
