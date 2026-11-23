package com.example.app.support;

import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every error answer of every MockMvc call, whichever test makes it, is checked here: each refusal builds its body
 * on its own path (a mapped constraint, a state's refusal, a stale version), so a check in one test proves none of
 * the others.
 */
@Component
public class NoInternalsInErrors implements MockMvcBuilderCustomizer {

    private static final int FIRST_ERROR_STATUS = 400;

    static void assertNoInternals(String body) {
        assertThat(body).doesNotContain("uq_", "fk_", "ex_", "ck_", "pk_", "SQL", "Exception", "java.");
    }

    @Override
    public void customize(ConfigurableMockMvcBuilder<?> builder) {
        builder.alwaysDo(result -> {
            if (result.getResponse().getStatus() >= FIRST_ERROR_STATUS) {
                assertNoInternals(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
            }
        });
    }
}
