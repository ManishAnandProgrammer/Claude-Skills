package com.example.app.archunit;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaModifier;
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
    static final ArchRule responsesCarryNoOtherModulesContract = classes().that().resideInAPackage("..dto.response..")
            .should(notDependOnAnotherModulesApi())
            .because("""
                    a response that embeds another module's view changes whenever that contract does; map it into \
                    a record of your own (ARCH-06)""");

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

    private static ArchCondition<JavaClass> notDependOnAnotherModulesApi() {
        return new ArchCondition<>("not depend on another module's api package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                Optional<String> originModule = moduleOf(origin.getPackageName());
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    String targetPackage = dependency.getTargetClass().getPackageName();
                    Optional<String> targetModule = moduleOf(targetPackage);
                    boolean anotherModulesApi = targetModule.isPresent() && !targetModule.equals(originModule)
                            && isApiOf(targetModule.get(), targetPackage);
                    if (anotherModulesApi) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** Never a sibling that merely starts the same ({@code apiclient}). */
    private static boolean isApiOf(String module, String packageName) {
        String api = ROOT + module + ".api";
        return packageName.equals(api) || packageName.startsWith(api + ".");
    }

    /** {@code controller.api} is HTTP documentation, not a module contract. */
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
        return new ArchCondition<>("be a record, an interface, an enum or a constants holder") {
            @Override
            public void check(JavaClass javaClass, ConditionEvents events) {
                boolean contract = javaClass.isRecord() || javaClass.isInterface() || javaClass.isEnum() || isConstantsHolder(javaClass);
                if (!contract) {
                    events.add(SimpleConditionEvent.violated(javaClass, javaClass.getName() + " is a class"));
                }
            }
        };
    }

    /**
     * The compile-time constants an annotation needs (a pattern), which no record or enum can hold; an interface of
     * constants is Effective Java's item 22.
     */
    private static boolean isConstantsHolder(JavaClass javaClass) {
        boolean finalClass = javaClass.getModifiers().contains(JavaModifier.FINAL);
        boolean onlyConstants = javaClass.getFields().stream()
                .allMatch(field -> field.getModifiers().containsAll(Set.of(JavaModifier.STATIC, JavaModifier.FINAL)));
        boolean privateConstructors = javaClass.getConstructors().stream()
                .allMatch(constructor -> constructor.getModifiers().contains(JavaModifier.PRIVATE));
        return finalClass && onlyConstants && privateConstructors && javaClass.getMethods().isEmpty();
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
