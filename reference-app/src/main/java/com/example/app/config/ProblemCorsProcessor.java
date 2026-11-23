package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

@RequiredArgsConstructor
class ProblemCorsProcessor extends DefaultCorsProcessor {

    private final ProblemResponses problemResponses;

    @Override
    public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        boolean allowed = super.processRequest(configuration, request, response);
        if (!allowed) {
            problemResponses.write(request, response, ErrorCode.CORS_REQUEST_REFUSED);
        }
        return allowed;
    }

    /**
     * Writes nothing: the Problem Details' {@code instance} is the request path, and this method isn't given the
     * request. {@link #processRequest} writes the refusal.
     */
    @Override
    protected void rejectRequest(ServerHttpResponse response) {
    }
}
