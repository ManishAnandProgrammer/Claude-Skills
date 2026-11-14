package com.example.app.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.Dependency;

import jakarta.persistence.Entity;

import java.util.Optional;
import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Module boundaries (profile: {@code moduleContracts: api-facades}). Every module, {@code config}
 * included, reaches a feature module ({@code todo}, {@code user}) only through its {@code api} package;
 * {@code common} and {@code config} are infrastructure every module may use, and {@code common} depends
 * on no feature at all. An {@code api} package holds contracts only: records, interfaces and enums, with
 * no entity or layer type in them.
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
    static final ArchRule apiPackagesHoldOnlyContractTypes = classes().that(areInAModuleApi())
            .should(beARecordAnInterfaceOrAnEnum())
            .allowEmptyShould(true)
            .because("a module's api is a contract other modules compile against: plain data and interfaces, nothing that carries behaviour or state (ARCH-05)");

    @ArchTest
    static final ArchRule apiPackagesExposeNoInternals = noClasses().that(areInAModuleApi())
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .orShould().dependOnClassesThat().resideInAnyPackage("..domain..", "..repository..", "..service..", "..mapper..",
                    "..dto..", "..controller..", "com.example.app.config..")
            .allowEmptyShould(true)
            .because("renaming an internal type must never force a contract change across every consuming module (ARCH-05)");

    @ArchTest
    static final ArchRule commonDependsOnNoFeature = noClasses().that().resideInAPackage("com.example.app.common..")
            .should().dependOnClassesThat().resideOutsideOfPackages("com.example.app.common..", "java..", "javax..",
                    "jakarta..", "org.springframework..", "org.slf4j..", "lombok..", "io.swagger..", "io.micrometer..",
                    "io.github.resilience4j..", "io.github.bucket4j..", "io.lettuce..", "org.hibernate..",
                    "tools.jackson..", "com.fasterxml.jackson..", "org.springdoc..", "org.postgresql..")
            .because("common holds universal infrastructure only and must not know any feature (ARCH-08)");

    private static ArchCondition<JavaClass> onlyReachOtherModulesThroughTheirApi() {
        return new ArchCondition<>("only depend on another feature module's api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originModule = moduleOf(origin.getPackageName());
                if (originModule.isEmpty()) {
                    return;
                }
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    Optional<String> targetModule = moduleOf(targetPackage);
                    boolean crossesIntoAFeature = targetModule.isPresent()
                            && !targetModule.equals(originModule)
                            && !INFRASTRUCTURE.contains(targetModule.get());
                    if (crossesIntoAFeature && !isApiOf(targetModule.get(), targetPackage)) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** {@code <module>.api} or below it; never a sibling that merely starts the same ({@code apiclient}). */
    private static boolean isApiOf(String module, String packageName) {
        String api = ROOT + module + ".api";
        return packageName.equals(api) || packageName.startsWith(api + ".");
    }

    /** {@code <module>.api..}; {@code controller.api} is HTTP documentation, not a module contract. */
    private static DescribedPredicate<JavaClass> areInAModuleApi() {
        return new DescribedPredicate<>("are in a module's api package") {
            @Override
            public boolean test(JavaClass javaClass) {
                Optional<String> module = moduleOf(javaClass.getPackageName());
                return module.isPresent() && isApiOf(module.get(), javaClass.getPackageName());
            }
        };
    }

    private static ArchCondition<JavaClass> beARecordAnInterfaceOrAnEnum() {
        return new ArchCondition<>("be a record, an interface or an enum") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                if (!javaClass.isRecord() && !javaClass.isInterface() && !javaClass.isEnum()) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " is a class"));
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
