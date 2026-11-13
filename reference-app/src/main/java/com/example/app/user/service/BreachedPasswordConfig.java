package com.example.app.user.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.authentication.password.CompromisedPasswordChecker;
import org.springframework.security.web.authentication.password.HaveIBeenPwnedRestApiPasswordChecker;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration
class BreachedPasswordConfig {

    // Asks the range API to pad its answer with fake suffixes, so the response size doesn't reveal
    // how many breached passwords share the prefix (and so hint at the password).
    private static final String ADD_PADDING = "Add-Padding";

    // A RestClient replacing the checker's own needs the API's base URL: the checker requests only
    // the hash prefix. Ours adds timeouts (the default has none, so a hanging service would hold
    // every signup), counts failures, which the checker itself swallows, and observes each call
    // (http.client.requests, traceparent) through the registry: Boot's RestClient.Builder lives in a
    // module this application doesn't otherwise need.
    @Bean
    CompromisedPasswordChecker compromisedPasswordChecker(BreachedPasswordProperties properties,
                                                          MeterRegistry meterRegistry,
                                                          ObservationRegistry observationRegistry) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        RestClient restClient = RestClient.builder()
                .baseUrl(properties.apiUrl())
                .defaultHeader(ADD_PADDING, Boolean.TRUE.toString())
                .requestFactory(requestFactory)
                .observationRegistry(observationRegistry)
                .requestInterceptor(new FailureCountingInterceptor(BreachedPasswordCheck.unavailableCounter(meterRegistry)))
                .build();
        HaveIBeenPwnedRestApiPasswordChecker checker = new HaveIBeenPwnedRestApiPasswordChecker();
        checker.setRestClient(restClient);
        return checker;
    }
}
