package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ListWrapper;
import com.example.app.user.controller.api.MembershipApi;
import com.example.app.user.dto.response.MembershipResponse;
import com.example.app.user.service.MembershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/{version}/users/me/tenants")
@RequiredArgsConstructor
class MembershipController implements MembershipApi {

    private final MembershipService service;
    private final CurrentUser currentUser;

    @Override
    @GetMapping(version = "v1+")
    public ListWrapper<MembershipResponse> mine() {
        return service.mine(currentUser.id());
    }
}
