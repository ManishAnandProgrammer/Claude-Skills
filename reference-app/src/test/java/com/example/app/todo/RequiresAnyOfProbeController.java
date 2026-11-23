package com.example.app.todo;

import com.example.app.common.security.Permission;
import com.example.app.common.security.Requires;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A test-only endpoint, found by component scanning as any controller under the root package: no golden endpoint
 * accepts two permissions yet, so this one stands for a detail several reaches read.
 */
@RestController
@RequestMapping("/api/{version}/requires-any-of-probes")
class RequiresAnyOfProbeController {

    @GetMapping(version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires({Permission.TODO_READ_OWN, Permission.PLAN_READ_OWN})
    @ApiResponse(responseCode = "204", description = "The caller holds one of the two")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED")
    void anyOf() {
    }

    @GetMapping(path = "/one", version = "v1+")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Requires(Permission.TODO_READ_OWN)
    @ApiResponse(responseCode = "204", description = "The caller holds it")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED")
    void one() {
    }
}
