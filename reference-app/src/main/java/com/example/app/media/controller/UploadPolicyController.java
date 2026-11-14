package com.example.app.media.controller;

import com.example.app.common.security.AdminOnly;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.media.api.UploadPurpose;
import com.example.app.media.controller.api.UploadPolicyApi;
import com.example.app.media.dto.request.UploadPolicyUpdateRequest;
import com.example.app.media.dto.response.UploadPolicyResponse;
import com.example.app.media.service.UploadPolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/upload-policies")
@RequiredArgsConstructor
class UploadPolicyController implements UploadPolicyApi {

    private final UploadPolicyService service;

    @Override
    @GetMapping(version = "v1+")
    public ListWrapper<UploadPolicyResponse> all() {
        return service.findAll();
    }

    @Override
    @PutMapping(path = "/{purpose}", version = "v1+")
    @AdminOnly
    public ObjectWrapper<UploadPolicyResponse> update(@PathVariable UploadPurpose purpose,
                                                      @RequestBody @Valid UploadPolicyUpdateRequest request) {
        return service.update(purpose, request);
    }
}
