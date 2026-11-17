package com.example.app.common.admission;

import com.example.app.support.IntegrationTest;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CORS runs ahead of admission control, through the real filters: with the {@code api} group full, a refusal
 * carries the headers a browser needs to read its status and {@code Retry-After}, and a preflight is answered
 * without a permit. Each test takes every permit straight from the bulkhead and gives them back afterwards, so
 * the shared context goes on as before.
 */
class AdmissionCorsIntegrationTest extends IntegrationTest {

    /** The origin application-test.yml allows. */
    private static final String FRONTEND = "http://localhost:3000";
    private static final String API_PATH = "/api/v1/users/me";

    @Autowired
    private BulkheadRegistry bulkheads;

    private Bulkhead api;
    private int taken;

    @BeforeEach
    void takeEveryApiPermit() {
        api = bulkheads.bulkhead(AdmissionControlFilter.DEFAULT_GROUP);
        taken = 0;
        while (api.tryAcquirePermission()) {
            taken++;
        }
    }

    @AfterEach
    void giveThePermitsBack() {
        for (int permit = 0; permit < taken; permit++) {
            api.onComplete();
        }
    }

    @Test
    @DisplayName("A full api group's 503 SERVER_BUSY carries the CORS headers, Retry-After exposed, so a browser can read it")
    void aRefusalCarriesTheCorsHeaders() throws Exception {
        mvc.perform(get(API_PATH).header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("SERVER_BUSY"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, containsString(HttpHeaders.RETRY_AFTER)));
    }

    @Test
    @DisplayName("A preflight is answered while the api group is full: it takes no permit")
    void aPreflightTakesNoPermit() throws Exception {
        mvc.perform(options(API_PATH).header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }
}
