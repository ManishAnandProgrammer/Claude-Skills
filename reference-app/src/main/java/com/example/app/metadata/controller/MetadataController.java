package com.example.app.metadata.controller;

import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.metadata.controller.api.MetadataApi;
import com.example.app.metadata.dto.request.MetadataUpdateRequest;
import com.example.app.metadata.dto.response.MetadataResponse;
import com.example.app.metadata.service.MetadataService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/metadata")
@RequiredArgsConstructor
class MetadataController implements MetadataApi {

    private final MetadataService service;

    @Override
    @GetMapping(path = "/{key}", version = "v1+")
    @Requires(Permission.METADATA_MANAGE)
    public ObjectWrapper<MetadataResponse> get(@PathVariable String key) {
        return service.findByName(key);
    }

    @Override
    @PutMapping(path = "/{key}", version = "v1+")
    @Requires(Permission.METADATA_MANAGE)
    public ObjectWrapper<MetadataResponse> update(@PathVariable String key, @RequestBody @Valid MetadataUpdateRequest request) {
        return service.updateByName(key, request);
    }
}
