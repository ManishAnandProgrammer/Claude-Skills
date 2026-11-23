package com.example.app.archunit.fixture.security;

import com.example.app.common.security.Public;
import com.example.app.common.security.SignedIn;
import org.springframework.web.bind.annotation.GetMapping;

public class HandlerDeclarations {

    @GetMapping("/none")
    public void declaresNothing() {
    }

    @GetMapping("/two")
    @SignedIn
    @Public
    public void declaresTwo() {
    }

    @GetMapping("/one")
    @SignedIn
    public void declaresOne() {
    }
}
