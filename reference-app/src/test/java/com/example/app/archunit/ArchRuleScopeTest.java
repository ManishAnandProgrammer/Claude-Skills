package com.example.app.archunit;

import com.example.app.archunit.api.FixtureCodes;
import com.example.app.archunit.api.FixtureHelper;
import com.example.app.archunit.fixture.dto.RangeRequest;
import com.example.app.archunit.fixture.logging.JulCall;
import com.example.app.archunit.fixture.settings.CheckedSettings;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the ArchUnit rules refuse and what they let pass, proven on the fixture classes beside this test
 * (the rules themselves analyse production code only). A narrowed rule keeps refusing what it is for.
 */
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

    private static boolean violates(ArchRule rule, Class<?> fixture) {
        JavaClasses classes = new ClassFileImporter().importClasses(fixture);
        return rule.allowEmptyShould(true).evaluate(classes).hasViolation();
    }
}
