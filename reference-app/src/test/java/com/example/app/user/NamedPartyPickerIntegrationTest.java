package com.example.app.user;

import com.example.app.common.security.Permission;
import com.example.app.support.IntegrationTest;
// roles:begin
// import com.example.app.support.StatementCounter;
// roles:end
import com.example.app.user.api.NamedParty;
import com.example.app.user.domain.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The picker's own contract, for every write a {@link NamedParty} is named by: an entity's tests prove its writes' side. */
class NamedPartyPickerIntegrationTest extends IntegrationTest {

    private static final String PICKER = "/api/v1/users/dropdown";

    static Stream<Permission> namingWrites() {
        return Arrays.stream(Permission.values()).filter(write -> NamedParty.namedBy(write).isPresent());
    }

    private static NamedParty partyNamedBy(Permission write) {
        return NamedParty.namedBy(write).orElseThrow();
    }

    private static Role roleHolding(Permission... permissions) {
        return Arrays.stream(Role.values())
                .filter(role -> Arrays.stream(permissions).allMatch(role::grants))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no role holds " + List.of(permissions)));
    }

    private TestUser named(TestUser user, String fullName) {
        assertThat(sql("update users set full_name = ? where id = ?", fullName, user.id())).isOne();
        return user;
    }

    private TestUser holderOf(Permission permission, String fullName) throws Exception {
        return named(newUserWithRole(roleHolding(permission).name()), fullName);
    }

    /** A holder of the party's permission too where a role holds both, so the picker could list the caller. */
    private static Role callerRole(Permission write) {
        return Arrays.stream(Role.values())
                .filter(role -> role.grants(write) && role.grants(partyNamedBy(write).mustHold()))
                .findFirst()
                .orElseGet(() -> roleHolding(write));
    }

    private TestUser callerOf(Permission write, String fullName) throws Exception {
        return named(newUserWithRole(callerRole(write).name()), fullName);
    }

    private String picker(Permission write, String query) {
        return PICKER + "?for=" + write.name() + "&query=" + query;
    }

    private long statementsFor(TestUser caller, String path) throws Exception {
        statistics().clear();
        // roles:begin
        // int queries = StatementCounter.countTemplateQueries(() -> getAs(caller.accessToken(), path).andExpect(status().isOk()));
        // assertThat(statistics().getPrepareStatementCount()).as("Hibernate's statements").isZero();
        // // The holders are read through the same template as the caller's access, which every request reads once.
        // return queries - 1;
        // roles:else
        getAs(caller.accessToken(), path).andExpect(status().isOk());
        return statistics().getPrepareStatementCount();
        // roles:end
    }

    @ParameterizedTest(allowZeroInvocations = true)
    @MethodSource("namingWrites")
    @DisplayName("""
            The picker for a write lists the active holders of its party's permission by name, a suspended one left \
            out, and its caller only when the party may be the owner""")
    void thePickerListsTheActiveHoldersOfTheParty(Permission write) throws Exception {
        NamedParty party = partyNamedBy(write);
        String prefix = "Picked " + UUID.randomUUID();
        TestUser holder = holderOf(party.mustHold(), prefix + " Ann");
        TestUser caller = callerOf(write, prefix + " Bob");
        TestUser away = holderOf(party.mustHold(), prefix + " Cid");
        assertThat(sql("update users set status = 'SUSPENDED' where id = ?", away.id())).isOne();
        List<String> expected = party.mayBeTheOwner() && callerRole(write).grants(party.mustHold())
                ? List.of(holder.id().toString(), caller.id().toString())
                : List.of(holder.id().toString());

        getAs(caller.accessToken(), picker(write, prefix))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.response[*].value", contains(expected.toArray())))
                .andExpect(jsonPath("$.response[0].label").value(prefix + " Ann"))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(false));
    }

    @ParameterizedTest(allowZeroInvocations = true)
    @MethodSource("namingWrites")
    @DisplayName("A caller holding every permission but the write is 403 ACCESS_DENIED, before any statement")
    void aWriteTheCallerDoesntHoldIs403(Permission write) throws Exception {
        TestUser caller = callerOf(write, "Refused " + UUID.randomUUID());
        Set<Permission> allButIt = EnumSet.complementOf(EnumSet.of(write));
        // Whatever a caller's first request reads once (and later requests don't) is read here, before the count.
        getAs(caller.accessToken(), picker(write, "warm")).andExpect(status().isOk());

        statistics().clear();
        mvc.perform(get(picker(write, "x")).with(holding(caller.accessToken(), allButIt)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACCESS_DENIED"));
        assertThat(statistics().getPrepareStatementCount()).isZero();
    }

    @ParameterizedTest(allowZeroInvocations = true)
    @MethodSource("namingWrites")
    @DisplayName("A batch costs exactly one statement: empty, of one holder and full with more to come")
    void eachBatchIsOneStatement(Permission write) throws Exception {
        NamedParty party = partyNamedBy(write);
        String prefix = "Counted " + UUID.randomUUID();
        TestUser caller = callerOf(write, "Counting " + UUID.randomUUID());
        holderOf(party.mustHold(), prefix + " Ann");
        getAs(caller.accessToken(), picker(write, "warm")).andExpect(status().isOk());

        assertThat(statementsFor(caller, picker(write, "Nobody " + UUID.randomUUID()))).isOne();
        assertThat(statementsFor(caller, picker(write, prefix))).isOne();
        holderOf(party.mustHold(), prefix + " Bob");
        getAs(caller.accessToken(), picker(write, prefix) + "&size=1")
                .andExpect(jsonPath("$.response.length()").value(1))
                .andExpect(jsonPath("$.pageInfo.hasMore").value(true));
        assertThat(statementsFor(caller, picker(write, prefix) + "&size=1")).isOne();
    }

    @Test
    @DisplayName("A held for whose write names nobody is 400 REQUEST_PARAMETER_INVALID at for")
    void aForNamingNobodyIs400() throws Exception {
        Permission namesNobody = Arrays.stream(Permission.values())
                .filter(permission -> NamedParty.namedBy(permission).isEmpty())
                .findFirst().orElseThrow();
        TestUser caller = newUser();

        mvc.perform(get(PICKER + "?for=" + namesNobody.name()).with(holding(caller.accessToken(), Set.of(namesNobody))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("for")));
    }

    @Test
    @DisplayName("""
            Without for the picker is 400 REQUEST_PARAMETER_MISSING at for, with one that is no permission 400 \
            REQUEST_PARAMETER_INVALID at for""")
    void thePickerNeedsAKnownFor() throws Exception {
        String token = newUser().accessToken();

        getAs(token, PICKER)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_MISSING"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("for")));
        getAs(token, PICKER + "?for=NOT_A_PERMISSION")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                .andExpect(jsonPath("$.errors[*].parameter", contains("for")));
    }

    @Test
    @DisplayName("A status other than ACTIVE is 400 REQUEST_PARAMETER_INVALID at status: the picker lists active accounts only")
    void aStatusOtherThanActiveIs400() throws Exception {
        Permission held = Permission.values()[0];
        TestUser caller = newUser();

        for (String status : List.of("INACTIVE", "ALL")) {
            mvc.perform(get(PICKER + "?for=" + held.name() + "&status=" + status).with(holding(caller.accessToken(), Set.of(held))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errorCode").value("REQUEST_PARAMETER_INVALID"))
                    .andExpect(jsonPath("$.errors[*].parameter", contains("status")));
        }
    }
}
