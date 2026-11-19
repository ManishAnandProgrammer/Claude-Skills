package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PersonAccessApi;
import com.example.app.user.dto.request.PersonalGrantRequest;
import com.example.app.user.dto.request.RoleAssignmentRequest;
import com.example.app.user.dto.response.PersonAccessResponse;
import com.example.app.user.service.PersonAccessService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** A person's access: their roles and personal grants (the roles piece). */
@RestController
@RequestMapping("/api/{version}/users")
@RequiredArgsConstructor
class PersonAccessController implements PersonAccessApi {

    private final PersonAccessService service;
    private final CurrentUser currentUser;

    @Override
    @GetMapping(path = "/{userId}/access", version = "v1+")
    @Requires(Permission.ACCESS_GRANT)
    public ObjectWrapper<PersonAccessResponse> access(@PathVariable UUID userId) {
        return service.access(userId);
    }

    @Override
    @PutMapping(path = "/{userId}/roles/{roleId}", version = "v1+")
    @Requires(Permission.ACCESS_GRANT)
    public ObjectWrapper<PersonAccessResponse> assignRole(@PathVariable UUID userId, @PathVariable UUID roleId,
                                                          @RequestBody @Valid RoleAssignmentRequest request) {
        return service.assignRole(currentUser.id(), userId, roleId, request);
    }

    @Override
    @DeleteMapping(path = "/{userId}/roles/{roleId}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.ACCESS_GRANT)
    public void revokeRole(@PathVariable UUID userId, @PathVariable UUID roleId) {
        service.revokeRole(currentUser.id(), userId, roleId);
    }

    @Override
    @PutMapping(path = "/{userId}/permissions/{permission}", version = "v1+")
    @Requires(Permission.ACCESS_GRANT)
    public ObjectWrapper<PersonAccessResponse> grant(@PathVariable UUID userId, @PathVariable Permission permission,
                                                     @RequestBody @Valid PersonalGrantRequest request) {
        return service.grant(currentUser.id(), userId, permission, request);
    }

    @Override
    @DeleteMapping(path = "/{userId}/permissions/{permission}", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.ACCESS_GRANT)
    public void removeGrant(@PathVariable UUID userId, @PathVariable Permission permission) {
        service.removeGrant(currentUser.id(), userId, permission);
    }
}
