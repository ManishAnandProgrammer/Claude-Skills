package com.example.app.user.controller;

import com.example.app.common.security.AdminOnly;
import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.TenantMemberApi;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberUpdateRequest;
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.service.MembershipService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/tenant-members")
@RequiredArgsConstructor
@AdminOnly
class TenantMemberController implements TenantMemberApi {

    private final MembershipService service;
    private final CurrentUser currentUser;

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    public ObjectWrapper<TenantMemberResponse> add(@RequestBody @Valid TenantMemberAddRequest request) {
        return service.addMember(request);
    }

    @Override
    @PutMapping(path = "/{userId}", version = "v1+")
    public ObjectWrapper<TenantMemberResponse> update(@PathVariable UUID userId,
                                                      @RequestBody @Valid TenantMemberUpdateRequest request) {
        return service.changeRole(currentUser.id(), userId, request);
    }

    @Override
    @DeleteMapping(path = "/{userId}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@PathVariable UUID userId) {
        service.removeMember(currentUser.id(), userId);
    }
}
