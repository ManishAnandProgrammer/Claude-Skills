package com.example.app.user;

import com.example.app.common.security.Area;
import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What a token grants: its role's permissions as its authorities, and GET /users/me/access showing them. */
class AccessIntegrationTest extends IntegrationTest {

    private static final String ACCESS = "/api/v1/users/me/access";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private JwtAuthenticationConverter converter;

    private static Jwt tokenWithRoles(List<String> roles) {
        return Jwt.withTokenValue("probe").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim("roles", roles).build();
    }

    /** Every authority but Spring Security's own factor one ({@code FACTOR_BEARER}: how the caller signed in). */
    private List<String> authoritiesOf(Jwt token) {
        List<String> authorities = converter.convert(token).getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(authorities).contains(FactorGrantedAuthority.BEARER_AUTHORITY).noneMatch(authority -> authority.startsWith("ROLE_"));
        return authorities.stream().filter(authority -> !authority.equals(FactorGrantedAuthority.BEARER_AUTHORITY)).toList();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("A token's authorities are exactly its role's permissions, by name, beside Spring's FACTOR_BEARER: no ROLE_ authority, nothing else")
    void aRoleBecomesExactlyItsPermissions(Role role) {
        List<String> expected = role.permissions().stream().map(Permission::name).toList();

        assertThat(authoritiesOf(tokenWithRoles(List.of(role.name())))).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("An unknown role, or none, grants nothing: unknown codes fail closed")
    void anUnknownRoleGrantsNothing() {
        assertThat(authoritiesOf(tokenWithRoles(List.of("NOT_A_ROLE")))).isEmpty();
        assertThat(authoritiesOf(tokenWithRoles(List.of()))).isEmpty();
        assertThat(authoritiesOf(Jwt.withTokenValue("probe").header("alg", "HS256").subject(UUID.randomUUID().toString())
                .claim("scope", "admin").build())).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Role.class)
    @DisplayName("/users/me/access lists the caller's permissions and the areas they make, in the enums' order; never the role")
    void accessShowsTheTokensPermissionsAndAreas(Role role) throws Exception {
        TestUser user = role == Role.USER ? newUser() : newUserWithRole(role.name());
        Set<Permission> permissions = EnumSet.noneOf(Permission.class);
        permissions.addAll(role.permissions());
        Set<Area> areas = EnumSet.noneOf(Area.class);
        for (Permission permission : permissions) {
            areas.add(permission.area());
        }

        String body = getAs(user.accessToken(), ACCESS).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        JsonNode response = JSON.readTree(body).path("response");
        assertThat(response.path("permissions").values().stream().map(JsonNode::asString).toList())
                .containsExactlyElementsOf(permissions.stream().map(Permission::name).toList());
        assertThat(response.path("areas").values().stream().map(JsonNode::asString).toList())
                .containsExactlyElementsOf(areas.stream().map(Area::name).toList());
        assertThat(response.propertyNames()).containsExactlyInAnyOrder("areas", "permissions");
    }

    @Test
    @DisplayName("Permission and Area are enum schemas of their own in the OpenAPI document, and /users/me/access refers to them")
    void apiDocsPublishThePermissionAndAreaEnums() throws Exception {
        JsonNode docs = JSON.readTree(getAs(null, "/v3/api-docs").andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode schemas = docs.path("components").path("schemas");

        assertThat(schemas.path("Permission").path("enum").values().stream().map(JsonNode::asString).toList())
                .containsExactlyElementsOf(Arrays.stream(Permission.values()).map(Permission::name).toList());
        assertThat(schemas.path("Area").path("enum").values().stream().map(JsonNode::asString).toList())
                .containsExactlyElementsOf(Arrays.stream(Area.values()).map(Area::name).toList());
        JsonNode access = schemas.path("AccessResponse").path("properties");
        assertThat(access.path("permissions").path("items").path("$ref").asString()).isEqualTo("#/components/schemas/Permission");
        assertThat(access.path("areas").path("items").path("$ref").asString()).isEqualTo("#/components/schemas/Area");
    }
}
