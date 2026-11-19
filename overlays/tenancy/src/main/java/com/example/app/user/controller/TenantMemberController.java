package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.common.wrapper.PaginationWrapper;
import com.example.app.user.controller.api.TenantMemberApi;
import com.example.app.user.dto.request.TenantMemberAddRequest;
import com.example.app.user.dto.request.TenantMemberFilter;
// roles:begin
// roles:else
// import com.example.app.user.dto.request.TenantMemberUpdateRequest;
// roles:end
import com.example.app.user.dto.response.TenantMemberResponse;
import com.example.app.user.service.MembershipService;
// roles:begin
import com.example.app.user.service.TenantMemberManager;
// roles:end
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
// roles:begin
// roles:else
// import org.springframework.web.bind.annotation.PutMapping;
// roles:end
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/{version}/tenant-members")
@RequiredArgsConstructor
class TenantMemberController implements TenantMemberApi {

    private final MembershipService service;
    // roles:begin
    /** Adding and removing a member changes their access too (the roles piece). */
    private final TenantMemberManager manager;
    // roles:end
    private final CurrentUser currentUser;

    @Override
    @GetMapping(version = "v1+")
    @Requires(Permission.TENANT_MEMBERS_MANAGE)
    public PaginationWrapper<TenantMemberResponse> all(@Valid TenantMemberFilter filter) {
        return service.findMembers(filter);
    }

    @Override
    @GetMapping(path = "/{userId}", version = "v1+")
    @Requires(Permission.TENANT_MEMBERS_MANAGE)
    public ObjectWrapper<TenantMemberResponse> get(@PathVariable UUID userId) {
        return service.findMember(userId);
    }

    @Override
    @PostMapping(version = "v1+")
    @ResponseStatus(HttpStatus.CREATED)
    @Requires(Permission.TENANT_MEMBERS_MANAGE)
    public ObjectWrapper<TenantMemberResponse> add(@RequestBody @Valid TenantMemberAddRequest request) {
        // roles:begin
        return manager.add(currentUser.id(), request);
        // roles:else
        // return service.addMember(currentUser.id(), request);
        // roles:end
    }
    // roles:begin
    // roles:else
    //
    // @Override
    // @PutMapping(path = "/{userId}", version = "v1+")
    // @Requires(Permission.TENANT_MEMBERS_MANAGE)
    // public ObjectWrapper<TenantMemberResponse> update(@PathVariable UUID userId,
    //                                                   @RequestBody @Valid TenantMemberUpdateRequest request) {
    //     return service.changeRole(currentUser.id(), userId, request);
    // }
    // roles:end

    @Override
    @DeleteMapping(path = "/{userId}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.TENANT_MEMBERS_MANAGE)
    public void remove(@PathVariable UUID userId) {
        // roles:begin
        manager.remove(currentUser.id(), userId);
        // roles:else
        // service.removeMember(currentUser.id(), userId);
        // roles:end
    }
}
