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

    // Fake suffixes in the answer: its size doesn't reveal how many breached passwords share the prefix.
    private static final String ADD_PADDING = "Add-Padding";

    // The checker's own RestClient has no timeouts: a hanging service would hold every signup. A replacement
    // needs the API's base URL (the checker requests only the hash prefix), and is built here because Boot's
    // RestClient.Builder lives in a module this application doesn't otherwise need.
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
                .observationConvention(new RangeLookupObservationConvention())
                .requestInterceptor(new FailureCountingInterceptor(BreachedPasswordCheck.unavailableCounter(meterRegistry)))
                .build();
        HaveIBeenPwnedRestApiPasswordChecker checker = new HaveIBeenPwnedRestApiPasswordChecker();
        checker.setRestClient(restClient);
        return checker;
    }
}
