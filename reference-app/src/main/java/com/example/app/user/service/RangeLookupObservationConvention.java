package com.example.app.user.service;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.HighCardinalityKeyNames;
import org.springframework.http.client.observation.ClientHttpObservationDocumentation.LowCardinalityKeyNames;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Names every breached-password lookup by one URI. The checker requests {@code <base>/<prefix>} as a
 * literal string, so the default convention would tag each of the 16^5 SHA-1 prefixes as its own
 * {@code uri}: a timer per prefix, never removed, and password-derived values in the metrics and
 * trace backends.
 */
class RangeLookupObservationConvention extends DefaultClientRequestObservationConvention {

    static final String URI = "/range/{prefix}";

    @Override
    protected KeyValue uri(ClientRequestObservationContext context) {
        return KeyValue.of(LowCardinalityKeyNames.URI, URI);
    }

    @Override
    public KeyValues getHighCardinalityKeyValues(ClientRequestObservationContext context) {
        return KeyValues.of(KeyValue.of(HighCardinalityKeyNames.HTTP_URL, URI));
    }
}
