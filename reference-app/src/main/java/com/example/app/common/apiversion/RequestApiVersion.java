package com.example.app.common.apiversion;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.accept.ApiVersionHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Optional;

/**
 * The API version Spring resolved for a request, whatever carried it (path, header, ...). Present
 * only once Spring parsed it and found it supported, so it is always one of the configured
 * versions: safe as a metric tag. Empty for a request outside the API or with a refused version.
 */
public final class RequestApiVersion {

    private RequestApiVersion() {
    }

    public static Optional<String> of(HttpServletRequest request) {
        if (request.getAttribute(HandlerMapping.API_VERSION_ATTRIBUTE) instanceof ApiVersionHolder holder
                && holder.hasVersion()) {
            return Optional.of(holder.getVersion().toString());
        }
        return Optional.empty();
    }
}
