package com.example.app.archunit;

import com.example.app.archunit.api.FixtureCodes;
import com.example.app.archunit.api.FixtureHelper;
import com.example.app.archunit.fixture.common.security.RoleCheckAnnotation;
import com.example.app.archunit.fixture.dto.RangeRequest;
import com.example.app.archunit.fixture.logging.JulCall;
import com.example.app.archunit.fixture.security.HandlerDeclarations;
import com.example.app.archunit.fixture.security.PostAuthorizeCheck;
import com.example.app.archunit.fixture.security.RoleChecks;
import com.example.app.archunit.fixture.security.SecuredClass;
import com.example.app.archunit.fixture.security.StringChecks;
import com.example.app.archunit.fixture.settings.CheckedSettings;
import com.example.app.archunit.fixture.settings.UntypedSettings;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The fixture classes beside this test break the rules on purpose: the rules themselves analyse production code only. */
class ArchRuleScopeTest {

    private static final String HIDDEN = "com.example.app.archunit.fixture.visibility.Hidden";

    @Test
    @DisplayName("A request's @AssertTrue is refused; a settings record's startup check is not a request (ERR-07)")
    void crossFieldRuleCoversRequestsOnly() {
        assertThat(violates(ConventionArchTest.crossFieldRulesNameTheirField, RangeRequest.class)).isTrue();
        assertThat(violates(ConventionArchTest.crossFieldRulesNameTheirField, CheckedSettings.class)).isFalse();
    }

    @Test
    @DisplayName("A module's api may hold a constants class; a class with behaviour stays refused (ARCH-05)")
    void apiPackagesAcceptConstantsHolders() {
        assertThat(violates(ModuleBoundaryArchTest.apiPackagesHoldOnlyContractTypes, FixtureHelper.class)).isTrue();
        assertThat(violates(ModuleBoundaryArchTest.apiPackagesHoldOnlyContractTypes, FixtureCodes.class)).isFalse();
    }

    @Test
    @DisplayName("java.util.logging is refused when called, not only when stored in a field")
    void javaUtilLoggingIsRefusedWhenCalled() {
        assertThat(violates(ConventionArchTest.noJavaUtilLogging, JulCall.class)).isTrue();
    }

    @Test
    @DisplayName("A public member naming a package-private type is refused, a type argument included; a narrowed member or a package-private class passes (JAVA-27)")
    void membersExposeNoNarrowerType() {
        JavaClasses visibility = new ClassFileImporter().importPackages("com.example.app.archunit.fixture.visibility");

        List<String> violations = ConventionArchTest.membersExposeNoNarrowerType.evaluate(visibility).getFailureReport().getDetails();

        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(violation -> violation.contains("ExposingConstructor.<init>(" + HIDDEN + ")"));
        assertThat(violations).anyMatch(violation -> violation.contains("ExposingTypeArgument.all()"));
    }

    @Test
    @DisplayName("""
            A settings record's number with its unit in its name, or text with a grammar, is refused, in a held record \
            and a map's values too; the same values typed by what they mean pass, and a count stays a number (CFG-12)""")
    void settingsAreTypedByWhatTheyMean() {
        JavaClasses settings = new ClassFileImporter().importPackages("com.example.app.archunit.fixture.settings");

        List<String> violations = ConventionArchTest.settingsAreTypedByWhatTheyMean.evaluate(settings).getFailureReport().getDetails();

        assertThat(violations).hasSize(8);
        assertThat(violations).allMatch(violation -> violation.contains(UntypedSettings.class.getName()));
        for (String field : List.of("leaseMinutes", "timeoutSeconds", "UntypedSettings.seconds", "apiUrl", "UntypedSettings.cron",
                "Client.readTimeoutMs", "Client.endpoint", "Store.baseUri")) {
            assertThat(violations).anyMatch(violation -> violation.contains(field + " is"));
        }
        assertThat(violations).noneMatch(violation -> violation.contains("batchSize"));
    }

    @Test
    @DisplayName("A handler declaring none of @Requires, @SignedIn and @Public is refused, and one declaring two; exactly one passes (AUTHZ-03)")
    void handlersDeclareExactlyOne() {
        List<String> violations = ConventionArchTest.handlersDeclareWhoMayCallThem
                .evaluate(new ClassFileImporter().importClasses(HandlerDeclarations.class)).getFailureReport().getDetails();

        assertThat(violations).hasSize(2);
        assertThat(violations).anyMatch(violation -> violation.contains("declaresNothing()") && violation.contains("declares 0"));
        assertThat(violations).anyMatch(violation -> violation.contains("declaresTwo()") && violation.contains("declares 2"));
    }

    @Test
    @DisplayName("A method-security string outside common.security is refused on a method and on a class; inside it, it is how the annotations are declared (AUTHZ-03)")
    void methodSecurityStringsLiveInCommonSecurity() {
        assertThat(violates(ConventionArchTest.methodSecurityIsDeclaredOnce, StringChecks.class)).isTrue();
        assertThat(violates(ConventionArchTest.classMethodSecurityIsDeclaredOnce, StringChecks.class)).isTrue();
        assertThat(violates(ConventionArchTest.methodSecurityIsDeclaredOnce, PostAuthorizeCheck.class)).isTrue();
        assertThat(violates(ConventionArchTest.classMethodSecurityIsDeclaredOnce, PostAuthorizeCheck.class)).isTrue();
        assertThat(violates(ConventionArchTest.classMethodSecurityIsDeclaredOnce, RoleCheckAnnotation.class)).isFalse();
    }

    @Test
    @DisplayName("""
            A role check is refused everywhere, common.security included: in an expression (a ROLE_ authority too), \
            @Secured on a method or a class, @RolesAllowed, a call of hasRole, a RoleHierarchy (AUTHZ-02)""")
    void roleChecksAreRefusedEverywhere() {
        List<String> violations = ConventionArchTest.noRoleChecks
                .evaluate(new ClassFileImporter().importClasses(RoleChecks.class)).getFailureReport().getDetails();

        assertThat(violations).anyMatch(violation -> violation.contains("hasRole"));
        assertThat(violations).anyMatch(violation -> violation.contains(RoleHierarchy.class.getSimpleName()));
        assertThat(violates(ConventionArchTest.noRoleChecksOnMethods, RoleChecks.class)).isTrue();
        assertThat(ConventionArchTest.noRoleChecksOnMethods
                .evaluate(new ClassFileImporter().importClasses(RoleChecks.class)).getFailureReport().getDetails()).hasSize(4);
        assertThat(violates(ConventionArchTest.noRoleChecks, SecuredClass.class)).isTrue();
        assertThat(violates(ConventionArchTest.noRoleChecks, RoleCheckAnnotation.class)).isTrue();
    }

    private static boolean violates(ArchRule rule, Class<?> fixture) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        return rule.allowEmptyShould(true).evaluate(classes).hasViolation();
    }
}
