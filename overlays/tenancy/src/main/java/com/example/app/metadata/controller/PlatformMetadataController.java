package com.example.app.metadata.controller;

import com.example.app.common.security.SignedIn;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.metadata.controller.api.PlatformMetadataApi;
import com.example.app.metadata.dto.request.MetadataUpdateRequest;
import com.example.app.metadata.dto.response.MetadataResponse;
import com.example.app.metadata.service.PlatformMetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Control plane: a platform token only (SecurityConfig's platform chain). */
@RestController
@RequestMapping("/api/{version}/platform/tenants/{tenantId}/metadata")
@RequiredArgsConstructor
class PlatformMetadataController implements PlatformMetadataApi {

    private final PlatformMetadataService service;

    @Override
    @GetMapping(path = "/{key}", version = "v1+")
    @SignedIn
    public ObjectWrapper<MetadataResponse> get(@PathVariable UUID tenantId, @PathVariable String key) {
        return service.findByName(tenantId, key);
    }

    @Override
    @PutMapping(path = "/{key}", version = "v1+")
    @SignedIn
    public ObjectWrapper<MetadataResponse> update(@PathVariable UUID tenantId, @PathVariable String key,
                                                  @RequestBody @Valid MetadataUpdateRequest request) {
        return service.updateByName(tenantId, key, request);
    }
}
