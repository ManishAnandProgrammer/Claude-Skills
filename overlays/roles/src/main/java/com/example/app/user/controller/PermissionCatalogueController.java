package com.example.app.user.controller;

import com.example.app.common.security.SignedIn;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.PermissionCatalogueApi;
import com.example.app.user.dto.response.PermissionCatalogueResponse;
import com.example.app.user.service.AccessRoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/permissions")
@RequiredArgsConstructor
class PermissionCatalogueController implements PermissionCatalogueApi {

    private final AccessRoleService service;

    @Override
    @GetMapping(version = "v1+")
    @SignedIn
    public ObjectWrapper<PermissionCatalogueResponse> catalogue() {
        return service.catalogue();
    }
}
