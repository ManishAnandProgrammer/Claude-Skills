package com.example.app.archunit;

import com.example.app.common.scheduling.MultiInstanceSafe;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Lob;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.concurrent.Executors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

/** House conventions that live in bytecode (architecture.md "Conventions in bytecode"). */
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
    static final ArchRule noUnboundedExecutors = noClasses()
            .should().callMethod(Executors.class, "newFixedThreadPool", int.class)
            .orShould().callMethod(Executors.class, "newCachedThreadPool")
            .because("their queues or threads are unbounded (REL-09)");

    @ArchTest
    static final ArchRule noStandardStreams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;

    @ArchTest
    static final ArchRule noJavaUtilLogging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
