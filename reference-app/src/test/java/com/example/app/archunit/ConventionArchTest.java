package com.example.app.archunit;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Lob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/** House conventions that live in bytecode (architecture.md "Conventions in bytecode", "Naming"). */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class ConventionArchTest {

    @ArchTest
    static final ArchRule noLob = noFields().should().beAnnotatedWith(Lob.class)
            .because("@Lob String is a PostgreSQL large object (oid); long text is columnDefinition = \"text\" (DATA-12)");

    @ArchTest
    static final ArchRule noOptionalFields = noFields().should().haveRawType(Optional.class)
            .because("Optional is a return type only (JAVA-08)");

    @ArchTest
    static final ArchRule noPrivateTransactionalMethods = noMethods().that().arePrivate()
            .should().beAnnotatedWith(Transactional.class)
            .because("the proxy can't apply to a private method: the write would silently not be transactional (DATA-16)");

    @ArchTest
    static final ArchRule scheduledJobsDeclareTheirConcurrencyPosture = methods().that().areAnnotatedWith(Scheduled.class)
            .should().beAnnotatedWith(MultiInstanceSafe.class)
            .because("several instances run: a job is either safe on all of them at once, or takes a distributed lock (ARCH-12)");

    @ArchTest
    static final ArchRule noOptionalParameters = noCodeUnits().that().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should(haveAnOptionalParameter())
            .because("Optional is a return type only: a parameter takes the value or null, and overloads cover the absent case (JAVA-08)");

    @ArchTest
    static final ArchRule noUnboundedExecutors = noClasses()
            .should().callMethod(Executors.class, "newFixedThreadPool", int.class)
            .orShould().callMethod(Executors.class, "newFixedThreadPool", int.class, ThreadFactory.class)
            .orShould().callMethod(Executors.class, "newCachedThreadPool")
            .orShould().callMethod(Executors.class, "newCachedThreadPool", ThreadFactory.class)
            .because("their queues or threads are unbounded, whatever thread factory names them (REL-09)");

    @ArchTest
    static final ArchRule onlySpringTransactional = noClasses()
            .should().dependOnClassesThat().haveFullyQualifiedName("jakarta.transaction.Transactional")
            .because("the house rules (readOnly by default, propagation, rollback) are Spring's @Transactional; jakarta's has no readOnly (DATA-16)");

    @ArchTest
    static final ArchRule controllersAreNamedAsControllers = classes().that().areAnnotatedWith(RestController.class)
            .should().haveSimpleNameEndingWith("Controller")
            .because("a controller is found by its name (naming convention; its package is LayerArchTest's)");

    @ArchTest
    static final ArchRule controllerNamesMeanControllers = classes().that().haveSimpleNameEndingWith("Controller")
            .should().beAnnotatedWith(RestController.class)
            .because("a *Controller that isn't one misleads every reader (naming convention)");

    @ArchTest
    static final ArchRule repositoriesAreNamedAndPlacedAsRepositories = classes().that().areAssignableTo(Repository.class)
            .should().haveSimpleNameEndingWith("Repository")
            .andShould().resideInAPackage("..repository")
            .because("a Spring Data repository is found by its name and its package (naming convention)");

    @ArchTest
    static final ArchRule repositoryNamesLiveInRepositoryPackages = classes().that().haveSimpleNameEndingWith("Repository")
            .should().resideInAPackage("..repository..")
            .because("repositoryAccess is checked by package: a repository elsewhere escapes it (naming convention)");

    @ArchTest
    static final ArchRule repositoryImplementationsAreNotPublic = classes().that().haveSimpleNameEndingWith("RepositoryImpl")
            .should().notBePublic()
            .andShould().resideInAPackage("..repository.impl")
            .because("an implementation is reached through its repository interface only (naming convention)")
            // A fresh project has no custom repository yet.
            .allowEmptyShould(true);

    /** Imports the test classes itself: the rules above analyse production code only. */
    @ArchTest
    static void testClassesEndInTest(JavaClasses ignored) {
        JavaClasses tests = new ClassFileImporter().withImportOption(ImportOption.Predefined.ONLY_INCLUDE_TESTS)
                .importPackages("com.example.app");
        classes().that().areTopLevelClasses().and(containTests())
                .should().haveSimpleNameEndingWith("Test")
                .because("Surefire runs *Test classes: a test class named otherwise is silently never run")
                .check(tests);
    }

    private static ArchCondition<JavaCodeUnit> haveAnOptionalParameter() {
        return new ArchCondition<>("have an Optional parameter") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                boolean optional = codeUnit.getRawParameterTypes().stream().anyMatch(type -> type.isEquivalentTo(Optional.class));
                events.add(new SimpleConditionEvent(codeUnit, optional, codeUnit.getFullName() + " takes an Optional"));
            }
        };
    }

    private static DescribedPredicate<JavaClass> containTests() {
        return new DescribedPredicate<>("contain test methods") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getMethods().stream().anyMatch(ConventionArchTest::isTestMethod);
            }
        };
    }

    private static boolean isTestMethod(JavaMethod method) {
        return method.isAnnotatedWith(Test.class) || method.isAnnotatedWith(ParameterizedTest.class);
    }

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
