package com.example.app.common.apiversion;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.accept.ApiVersionHolder;
import org.springframework.web.servlet.HandlerMapping;

import java.util.Optional;

/**
 * Present only once Spring parsed the version and found it supported, so it is always one of the configured
 * versions: safe as a metric tag.
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
