package com.example.app.user.controller;

import com.example.app.common.security.CurrentUser;
import com.example.app.common.wrapper.ObjectWrapper;
import com.example.app.user.controller.api.UserApi;
import com.example.app.user.dto.response.UserResponse;
// session-version:begin
import com.example.app.user.service.AuthService;
// session-version:end
import com.example.app.user.service.UserService;
import lombok.RequiredArgsConstructor;
// session-version:begin
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
// session-version:end
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
// session-version:begin
import org.springframework.web.bind.annotation.ResponseStatus;
// session-version:end
import org.springframework.web.bind.annotation.RestController;

/** Any signed-in user, for their own account only (the id comes from the token, never the path). */
@RestController
@RequestMapping("/api/{version}/users")
@RequiredArgsConstructor
class UserController implements UserApi {

    private final UserService service;
    private final CurrentUser currentUser;
    // session-version:begin
    private final AuthService authService;
    // session-version:end

    @Override
    @GetMapping(path = "/me", version = "v1+")
    public ObjectWrapper<UserResponse> me() {
        return service.me(currentUser.id());
    }
    // session-version:begin

    @Override
    @DeleteMapping(path = "/me/sessions", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void endAllSessions() {
        authService.logoutEverywhere(currentUser.id());
    }
    // session-version:end
}
