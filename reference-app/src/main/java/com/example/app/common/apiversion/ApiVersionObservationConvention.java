package com.example.app.common.apiversion;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationProperties;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * {@code http.server.requests} with an {@code apiVersion} tag: the resolved version, or
 * {@code none} outside the API and for a refused version. Never the raw path segment, which a client
 * could fill with anything and so multiply the time series. The tag is always present: Prometheus
 * wants one set of tag keys per meter.
 */
@Component
class ApiVersionObservationConvention extends DefaultServerRequestObservationConvention {

    static final String API_VERSION = "apiVersion";
    static final String NONE = "none";

    // Hand-written (JAVA-26): the superclass takes the metric's name, which Lombok can't pass on.
    ApiVersionObservationConvention(ObservationProperties properties) {
        super(properties.getHttp().getServer().getRequests().getName());
    }

    @Override
    public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
        HttpServletRequest request = context.getCarrier();
        String version = Objects.isNull(request) ? NONE : RequestApiVersion.of(request).orElse(NONE);
        return super.getLowCardinalityKeyValues(context).and(KeyValue.of(API_VERSION, version));
    }
}
