package com.example.app.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The sign-in endpoints are rate limited per address with production's limits; without this every test request
 * would come from 127.0.0.1 and the tests would trip each other's limits.
 */
@TestConfiguration(proxyBeanMethods = false)
public class DistinctClientAddresses {

    private static final AtomicInteger ISSUED = new AtomicInteger();

    public static String unusedAddress() {
        int issued = ISSUED.incrementAndGet();
        return "10.%d.%d.%d".formatted(issued >>> 16 & 0xFF, issued >>> 8 & 0xFF, issued & 0xFF);
    }

    @Bean
    MockMvcBuilderCustomizer distinctClientAddressPerRequest() {
        return builder -> builder.defaultRequest(get("/").with(DistinctClientAddresses::assignAddress));
    }

    private static MockHttpServletRequest assignAddress(MockHttpServletRequest request) {
        if (MockHttpServletRequest.DEFAULT_REMOTE_ADDR.equals(request.getRemoteAddr())) {
            request.setRemoteAddr(unusedAddress());
        }
        return request;
    }
}
