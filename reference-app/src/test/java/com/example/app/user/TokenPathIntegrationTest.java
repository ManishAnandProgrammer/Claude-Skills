package com.example.app.user;

import com.example.app.support.IntegrationTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TokenPathIntegrationTest extends IntegrationTest {

    private static final String ME = "/api/v1/users/me";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    enum Subject {
        SUSPENDED_USER
    }

    enum Path {
        LOGIN,
        REFRESH,
        NEXT_REQUEST
    }

    // session-version:begin
    @ParameterizedTest(name = "{0} × {1}: {2} {3}")
    @CsvSource(delimiter = '|', textBlock = """
            SUSPENDED_USER | LOGIN        | 403 | AUTH_ACCOUNT_SUSPENDED
            SUSPENDED_USER | REFRESH      | 401 | AUTH_INVALID_REFRESH_TOKEN
            SUSPENDED_USER | NEXT_REQUEST | 401 | SESSION_VERSION_STALE
            """)
    // session-version:else
    // // An access token issued before the change lives out its lifetime (at most app.jwt.access-token-ttl).
    // @ParameterizedTest(name = "{0} × {1}: {2} {3}")
    // @CsvSource(delimiter = '|', textBlock = """
    //         SUSPENDED_USER | LOGIN        | 403 | AUTH_ACCOUNT_SUSPENDED
    //         SUSPENDED_USER | REFRESH      | 401 | AUTH_INVALID_REFRESH_TOKEN
    //         SUSPENDED_USER | NEXT_REQUEST | 200 | -
    //         """)
    // session-version:end
    void aChangeOfAccessReachesEveryTokenPath(Subject subject, Path path, int status, String errorCode) throws Exception {
        TestUser person = newUser();

        change(subject, person);
        MockHttpServletResponse answer = take(path, person);

        assertThat(answer.getStatus()).as(answer.getContentAsString()).isEqualTo(status);
        if (!errorCode.equals("-")) {
            assertThat(JSON.readTree(answer.getContentAsString()).path("errorCode").asString()).isEqualTo(errorCode);
        }
    }

    private void change(Subject subject, TestUser person) throws Exception {
        switch (subject) {
            case SUSPENDED_USER -> {
                Number version = read(getAs(newAdmin().accessToken(), "/api/v1/users?email=" + person.email())
                        .andExpect(status().isOk()), "$.response[0].version");
                patchAs(newAdmin().accessToken(), "/api/v1/users/" + person.id() + "/status", """
                        {"status": "SUSPENDED", "version": %d}
                        """.formatted(version.longValue())).andExpect(status().isOk());
            }
        }
    }

    private MockHttpServletResponse take(Path path, TestUser person) throws Exception {
        return switch (path) {
            case LOGIN -> login(person.email(), PASSWORD).andReturn().getResponse();
            case REFRESH -> postAs(null, "/api/v1/auth/refresh-token", """
                    {"refreshToken": "%s"}
                    """.formatted(person.refreshToken())).andReturn().getResponse();
            case NEXT_REQUEST -> getAs(person.accessToken(), ME).andReturn().getResponse();
        };
    }
}
