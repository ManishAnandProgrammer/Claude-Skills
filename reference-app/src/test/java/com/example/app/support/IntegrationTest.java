package com.example.app.support;

import com.example.app.TestcontainersConfiguration;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Objects;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base of every integration test: the whole application over MockMvc, one shared context and one
 * shared database. Create your own data (unique emails and names), never assume an empty table,
 * and don't add {@code @MockitoBean} or {@code @TestPropertySource}: either starts another context.
 *
 * <p>Fixture helpers assert their own success, so a setup that fails fails the test.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
public abstract class IntegrationTest {

    protected static final String PASSWORD = "Correct-Horse-9";

    @Autowired
    protected MockMvc mvc;

    /** For asserting database invariants (row counts) that no endpoint exposes. Writes go through {@link #sql}. */
    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * Runs a fixture write in its own committed transaction. The pool runs with auto-commit off, so
     * a bare {@code jdbc.update} outside a transaction is rolled back when its connection returns to
     * the pool, and the test would silently run against data that isn't there.
     */
    protected int sql(String statement, Object... args) {
        Integer rows = new TransactionTemplate(transactionManager).execute(status -> jdbc.update(statement, args));
        return rows;
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    /** Hibernate's counters (on in the test profile): clear them, run a request, read the statement count. */
    protected Statistics statistics() {
        return entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    }

    /** A signed-up user and their access token. */
    protected record TestUser(UUID id, String email, String accessToken, String refreshToken) {
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID() + "@example.com";
    }

    /** Signs up a new account and signs it in. */
    protected TestUser newUser() throws Exception {
        String email = uniqueEmail("user");
        String userId = read(signup(email, PASSWORD).andExpect(status().isCreated()), "$.response.id");
        ResultActions login = login(email, PASSWORD).andExpect(status().isOk());
        return new TestUser(UUID.fromString(userId), email,
                read(login, "$.response.accessToken"), read(login, "$.response.refreshToken"));
    }

    protected ResultActions signup(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/signup", """
                {"email": "%s", "fullName": "Test User", "password": "%s"}
                """.formatted(email, password));
    }

    protected ResultActions login(String email, String password) throws Exception {
        return postAs(null, "/api/v1/auth/login", """
                {"email": "%s", "password": "%s"}
                """.formatted(email, password));
    }

    protected ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(authorized(get(path), token));
    }

    protected ResultActions postAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(post(path), token), json));
    }

    protected ResultActions putAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(put(path), token), json));
    }

    protected ResultActions patchAs(String token, String path, String json) throws Exception {
        return mvc.perform(withJson(authorized(patch(path), token), json));
    }

    protected ResultActions deleteAs(String token, String path) throws Exception {
        return mvc.perform(authorized(delete(path), token));
    }

    protected static <T> T read(ResultActions result, String jsonPath) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), jsonPath);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request, String token) {
        if (Objects.nonNull(token)) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private static MockHttpServletRequestBuilder withJson(MockHttpServletRequestBuilder request, String json) {
        return request.contentType(MediaType.APPLICATION_JSON).content(json);
    }
}
