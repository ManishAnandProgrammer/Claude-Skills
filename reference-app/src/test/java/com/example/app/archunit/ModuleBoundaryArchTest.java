package com.example.app.archunit;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.Dependency;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module boundaries (profile: {@code moduleContracts: api-facades}). Feature modules ({@code todo},
 * {@code user}) reach each other only through the other's {@code api} package; {@code common} and
 * {@code config} are infrastructure, and {@code common} depends on no feature.
 */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class ModuleBoundaryArchTest {

    private static final String ROOT = "com.example.app.";
    private static final Set<String> INFRASTRUCTURE = Set.of("common", "config");

    @ArchTest
    static final ArchRule modulesAreFreeOfCycles = slices().matching("com.example.app.(*)..")
            .should().beFreeOfCycles()
            .because("a cycle between modules means neither can change or be extracted alone (ARCH-02)");

    @ArchTest
    static final ArchRule modulesUseOnlyEachOthersApi = classes().that().resideInAPackage("com.example.app..")
            .should(onlyReachOtherModulesThroughTheirApi())
            .because("entities, repositories, DTOs and services stay inside their module; other modules use <module>.api (ARCH-04, ARCH-06)");

    @ArchTest
    static final ArchRule commonDependsOnNoFeature = noClasses().that().resideInAPackage("com.example.app.common..")
            .should().dependOnClassesThat().resideOutsideOfPackages("com.example.app.common..", "java..", "javax..",
                    "jakarta..", "org.springframework..", "org.slf4j..", "lombok..", "io.swagger..", "io.micrometer..",
                    "io.github.resilience4j..", "io.github.bucket4j..", "io.lettuce..", "org.hibernate..",
                    "tools.jackson..", "com.fasterxml.jackson..", "org.springdoc..")
            .because("common holds universal infrastructure only and must not know any feature (ARCH-08)");

    private static ArchCondition<JavaClass> onlyReachOtherModulesThroughTheirApi() {
        return new ArchCondition<>("only depend on another feature module's api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originModule = moduleOf(origin.getPackageName());
                if (originModule.isEmpty() || INFRASTRUCTURE.contains(originModule.get())) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    Optional<String> targetModule = moduleOf(targetPackage);
                    boolean crossesIntoAFeature = targetModule.isPresent()
                            && !targetModule.equals(originModule)
                            && !INFRASTRUCTURE.contains(targetModule.get());
                    if (crossesIntoAFeature && !targetPackage.startsWith(ROOT + targetModule.get() + ".api")) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    private static Optional<String> moduleOf(String packageName) {
        if (!packageName.startsWith(ROOT)) {
            return Optional.empty();
        }
        String rest = packageName.substring(ROOT.length());
        int dot = rest.indexOf('.');
        return Optional.of(dot < 0 ? rest : rest.substring(0, dot));
    }
}
