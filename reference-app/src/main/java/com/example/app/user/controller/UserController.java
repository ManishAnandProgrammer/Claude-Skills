package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.UserApi;
import com.example.app.user.dto.response.UserResponse;
import com.example.app.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Any signed-in user, for their own account only (the id comes from the token, never the path). */
@RestController
@RequiredArgsConstructor
class UserController implements UserApi {

    private final UserService service;
    private final CurrentUser currentUser;

    @Override
    @GetMapping("/api/v1/users/me")
    public ObjectWrapper<UserResponse> me() {
        return service.me(currentUser.id());
    }
}
