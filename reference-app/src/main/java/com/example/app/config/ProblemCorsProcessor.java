package com.example.app.config;

import com.example.app.common.exception.ErrorCode;
import com.example.app.common.web.ProblemResponses;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;

import java.io.IOException;

/**
 * Spring's CORS checks, with the refusal written as Problem Details (403 {@code CORS_REQUEST_REFUSED}) like every
 * other error, instead of Spring's plain text {@code Invalid CORS request}. The refusal carries no
 * {@code Access-Control-Allow-Origin}, so a browser still keeps it from the page; a client outside one reads the code.
 */
class ProblemCorsProcessor extends DefaultCorsProcessor {

    private final ProblemResponses problemResponses;

    ProblemCorsProcessor(ProblemResponses problemResponses) {
        this.problemResponses = problemResponses;
    }

    /** {@code false} only after a refusal, with nothing written yet ({@link #rejectRequest}). */
    @Override
    public boolean processRequest(CorsConfiguration configuration, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        boolean allowed = super.processRequest(configuration, request, response);
        if (!allowed) {
            problemResponses.write(request, response, ErrorCode.CORS_REQUEST_REFUSED);
        }
        return allowed;
    }

    /** Writes nothing: the Problem Details' {@code instance} is the request path, and this method isn't given the request. */
    @Override
    protected void rejectRequest(ServerHttpResponse response) {
        // processRequest writes the refusal.
    }
}
