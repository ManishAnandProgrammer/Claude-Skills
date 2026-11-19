package com.example.app.archunit;

import com.example.app.common.limits.ConfiguredMax;
import com.example.app.common.scheduling.MultiInstanceSafe;
import com.example.app.common.security.Public;
import com.example.app.common.security.Requires;
import com.example.app.common.security.SignedIn;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Lob;
import jakarta.validation.constraints.AssertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.data.repository.Repository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.annotation.Secured;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.prepost.PostAuthorize;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.annotation.Annotation;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noCodeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

/** House conventions that live in bytecode (architecture.md "Conventions in bytecode", "Naming"). */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class ConventionArchTest {

    private static final String APP = "com.example.app";
    private static final String API = "/api";
    private static final String SECURITY = "..common.security..";
    private static final String ROLES_ALLOWED = "jakarta.annotation.security.RolesAllowed";
    private static final String VERSIONED_API = API + "/{version}/";
    private static final List<String> NUMBERS = List.of("int", "long", "double", Integer.class.getName(), Long.class.getName(),
            Double.class.getName());
    // A number with its unit in its name: the unit belongs in the value (a Duration, a Period, a DataSize).
    private static final Pattern UNIT_IN_THE_NAME = Pattern.compile(
            ".*(Ms|Millis|Seconds|Minutes|Hours|Days|Bytes|Kb|Mb)|millis|seconds|minutes|hours|days|bytes");
    // Text with a grammar: an address is a URI, a schedule a Cron. A host name alone is no URI, so *Host stays text.
    private static final Pattern TEXT_WITH_A_GRAMMAR = Pattern.compile(".*(Url|Uri|Endpoint|Cron)|url|uri|endpoint|cron");

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

    /** A project with a lock library adds its annotation: {@code .orShould().beAnnotatedWith(SchedulerLock.class)} (ShedLock). */
    @ArchTest
    static final ArchRule scheduledJobsDeclareTheirConcurrencyPosture = methods().that().areAnnotatedWith(Scheduled.class)
            .should().beAnnotatedWith(MultiInstanceSafe.class)
            .because("several instances run: a job is either safe on all of them at once, or takes a distributed lock (ARCH-12)");

    // Requests and filters only: a settings record's startup check (@AssertTrue on a @ConfigurationProperties record)
    // is no request, and no client field could name it.
    @ArchTest
    static final ArchRule crossFieldRulesNameTheirField = noMethods().that().areDeclaredInClassesThat().resideInAPackage("..dto..")
            .should().beAnnotatedWith(AssertTrue.class)
            .allowEmptyShould(true)
            .because("a rule across fields is @CrossField(reportedOn = ...): an @AssertTrue getter is reported on its own name, a field no client sends (ERR-07)");

    @ArchTest
    static final ArchRule derivedLimitsNameTheirField = methods().that().areAnnotatedWith(ConfiguredMax.class)
            // A getter, not a record component's accessor (which carries the component's annotation).
            .and().haveNameMatching("(get|is)[A-Z].*")
            .should(nameTheFieldTheyAreReportedOn())
            .because("a limit on a getter checks a number derived from fields: reportedOn names the field a form shows it under (ERR-07)")
            // A project without such a getter yet.
            .allowEmptyShould(true);

    /**
     * A settings record and the records it holds (a nested group, a value such as {@code RateLimit}). A bound in the
     * API's own unit ({@code time-entry.max-minutes} for a request field in minutes) is a {@code Limits} key, read by
     * key, so it never meets this rule.
     */
    @ArchTest
    static final ArchRule settingsAreTypedByWhatTheyMean = classes().that().areAnnotatedWith(ConfigurationProperties.class)
            .should(bindEachValueAsWhatItMeans())
            .because("""
                    a setting is bound as the type of what it means, parsed and checked once at startup: a time span is a \
                    Duration or a Period, a size a DataSize, an address a URI, a schedule a Cron, never a number with \
                    the unit in its name or text parsed later (CFG-12)""");

    @ArchTest
    static final ArchRule noOptionalParameters = noCodeUnits().that().doNotHaveModifier(JavaModifier.SYNTHETIC)
            .should(haveAnOptionalParameter())
            .because("Optional is a return type only: a parameter takes the value or null, and overloads cover the absent case (JAVA-08)");

    @ArchTest
    static final ArchRule membersExposeNoNarrowerType = classes().that().arePublic()
            .should(exposeOnlyTypesOtherPackagesCanName())
            .because("""
                    any package may call a public member, but none outside can name a package-private type it takes or \
                    returns: narrow the member (@RequiredArgsConstructor(access = AccessLevel.PACKAGE)), never widen the \
                    type (JAVA-27)""");

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
    static final ArchRule controllersMapOneVersionedResource = classes().that().areAnnotatedWith(RestController.class)
            .should(haveOneClassLevelPathUnder(VERSIONED_API))
            .because("one resource per controller, its path in one place, and every API path carries the version Spring's API versioning resolves (REST-07)");

    @ArchTest
    static final ArchRule handlerMethodsDeclareTheirVersion = methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and(areHandlerMethods())
            .should(declareAVersion())
            .because("an unversioned method answers every version, so a v2 could never change it; a baseline (v1+) carries it forward until a newer method replaces it (REST-07)");

    @ArchTest
    static final ArchRule handlerMethodsUseRelativePaths = methods().that().areDeclaredInClassesThat().areAnnotatedWith(RestController.class)
            .and(areHandlerMethods())
            .should(haveOnlyPathsRelativeToTheirController())
            .because("the controller's class-level mapping holds the resource path; a method repeating /api would map outside it (REST-07)");

    /** Any class with request mappings, a {@code @RestController} or not: a mapping without a declaration is the gap. */
    @ArchTest
    static final ArchRule handlersDeclareWhoMayCallThem = methods().that(areHandlerMethods())
            .should(declareExactlyOneOf(Requires.class, SignedIn.class, Public.class))
            .because("""
                    who may call a handler is read on the handler: the permission it @Requires, every signed-in caller \
                    (@SignedIn) or anyone (@Public); none is a forgotten check, two a contradiction (AUTHZ-03)""");

    @ArchTest
    static final ArchRule methodSecurityIsDeclaredOnce = noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage(SECURITY)
            .should().beAnnotatedWith(PreAuthorize.class)
            .orShould().beAnnotatedWith(PostAuthorize.class)
            .because("""
                    a check is @Requires(Permission.X), @SignedIn or @Public, declared once in common.security: an \
                    expression string names a permission or a role the compiler never checks (AUTHZ-03)""");

    @ArchTest
    static final ArchRule classMethodSecurityIsDeclaredOnce = noClasses().that().resideOutsideOfPackage(SECURITY)
            .should().beAnnotatedWith(PreAuthorize.class)
            .orShould().beAnnotatedWith(PostAuthorize.class)
            .because("""
                    a check is @Requires(Permission.X), @SignedIn or @Public on each handler, declared once in \
                    common.security: a class-level expression names what the compiler never checks (AUTHZ-03)""");

    /** Everywhere, common.security included: the house's authorities are permissions, never roles. */
    @ArchTest
    static final ArchRule noRoleChecks = noClasses()
            .should().beAnnotatedWith(aRoleCheckWrittenAsAString())
            .orShould().beAnnotatedWith(Secured.class)
            .orShould().beAnnotatedWith(ROLES_ALLOWED)
            .orShould().callMethodWhere(target(name("hasRole")).or(target(name("hasAnyRole"))))
            .orShould().dependOnClassesThat().areAssignableTo(RoleHierarchy.class)
            .because("""
                    a handler checks a permission, never a role: a role is a group of permissions (Role), so a role \
                    check misses the next role that holds the capability; no ROLE_ authority exists (AUTHZ-01, AUTHZ-02)""");

    @ArchTest
    static final ArchRule noRoleChecksOnMethods = noMethods()
            .should().beAnnotatedWith(aRoleCheckWrittenAsAString())
            .orShould().beAnnotatedWith(Secured.class)
            .orShould().beAnnotatedWith(ROLES_ALLOWED)
            .because("a handler checks a permission, never a role (AUTHZ-01, AUTHZ-02)");

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

    private static ArchCondition<JavaClass> haveOneClassLevelPathUnder(String prefix) {
        return new ArchCondition<>("have one class-level @RequestMapping path starting with " + prefix) {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class);
                boolean satisfied = Objects.nonNull(mapping) && mapping.path().length == 1 && mapping.path()[0].startsWith(prefix);
                events.add(new SimpleConditionEvent(controller, satisfied,
                        controller.getName() + " has no single class-level path under " + prefix));
            }
        };
    }

    /** {@code @PreAuthorize("hasRole('ADMIN')")}, a {@code 'ROLE_'} authority and the like, on a class, an annotation type or a method. */
    private static DescribedPredicate<JavaAnnotation<?>> aRoleCheckWrittenAsAString() {
        return new DescribedPredicate<>("@PreAuthorize or @PostAuthorize naming a role") {
            @Override
            public boolean test(JavaAnnotation<?> annotation) {
                boolean methodSecurity = annotation.getRawType().isEquivalentTo(PreAuthorize.class)
                        || annotation.getRawType().isEquivalentTo(PostAuthorize.class);
                if (!methodSecurity) {
                    return false;
                }
                String expression = String.valueOf(annotation.get("value").orElse(""));
                return expression.contains("hasRole(") || expression.contains("hasAnyRole(") || expression.contains("'ROLE_");
            }
        };
    }

    private static DescribedPredicate<JavaMethod> areHandlerMethods() {
        return new DescribedPredicate<>("are handler methods") {
            @Override
            public boolean test(JavaMethod method) {
                return Objects.nonNull(handlerMapping(method));
            }
        };
    }

    @SafeVarargs
    private static ArchCondition<JavaMethod> declareExactlyOneOf(Class<? extends Annotation>... declarations) {
        return new ArchCondition<>("declare exactly one of @Requires, @SignedIn, @Public") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                long declared = Arrays.stream(declarations).filter(method::isAnnotatedWith).count();
                events.add(new SimpleConditionEvent(method, declared == 1,
                        "%s declares %d of @Requires, @SignedIn, @Public".formatted(method.getFullName(), declared)));
            }
        };
    }

    private static ArchCondition<JavaMethod> declareAVersion() {
        return new ArchCondition<>("declare a version") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean versioned = !handlerMapping(method).version().isBlank();
                events.add(new SimpleConditionEvent(method, versioned, method.getFullName() + " declares no version"));
            }
        };
    }

    private static ArchCondition<JavaMethod> haveOnlyPathsRelativeToTheirController() {
        return new ArchCondition<>("have only paths relative to their controller") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean relative = Arrays.stream(handlerMapping(method).path()).noneMatch(path -> path.startsWith(API));
                events.add(new SimpleConditionEvent(method, relative, method.getFullName() + " repeats the /api path"));
            }
        };
    }

    /** The method's mapping with its composed annotation ({@code @GetMapping}, ...) merged in; null when it has none. */
    private static RequestMapping handlerMapping(JavaMethod method) {
        return AnnotatedElementUtils.findMergedAnnotation(method.reflect(), RequestMapping.class);
    }

    private static ArchCondition<JavaMethod> nameTheFieldTheyAreReportedOn() {
        return new ArchCondition<>("name the field they are reported on") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                boolean named = !method.reflect().getAnnotation(ConfiguredMax.class).reportedOn().isEmpty();
                events.add(new SimpleConditionEvent(method, named, method.getFullName() + " has @ConfiguredMax without reportedOn"));
            }
        };
    }

    private static ArchCondition<JavaClass> exposeOnlyTypesOtherPackagesCanName() {
        return new ArchCondition<>("expose only types other packages can name") {
            @Override
            public void check(JavaClass owner, ConditionEvents events) {
                if (!isVisibleEverywhere(owner)) {
                    return;
                }
                for (JavaMember member : owner.getMembers()) {
                    if (isVisibleOutsideItsPackage(member)) {
                        for (JavaClass named : typesNamedBy(member)) {
                            if (!isVisibleEverywhere(named)) {
                                events.add(SimpleConditionEvent.violated(member, member.getFullName() + " names " + named.getName()));
                            }
                        }
                    }
                }
            }
        };
    }

    private static boolean isVisibleOutsideItsPackage(JavaMember member) {
        Set<JavaModifier> modifiers = member.getModifiers();
        return !modifiers.contains(JavaModifier.SYNTHETIC)
                && (modifiers.contains(JavaModifier.PUBLIC) || modifiers.contains(JavaModifier.PROTECTED));
    }

    /** The field's type, or the parameter and return types, each with its type arguments and array components. */
    private static Set<JavaClass> typesNamedBy(JavaMember member) {
        Set<JavaClass> named = new HashSet<>();
        if (member instanceof JavaField field) {
            named.addAll(field.getType().getAllInvolvedRawTypes());
        }
        if (member instanceof JavaCodeUnit codeUnit) {
            for (JavaType parameter : codeUnit.getParameterTypes()) {
                named.addAll(parameter.getAllInvolvedRawTypes());
            }
            named.addAll(codeUnit.getReturnType().getAllInvolvedRawTypes());
        }
        return named;
    }

    /** Public, and so is every class it is nested in; a primitive or an array of a visible type too. */
    private static boolean isVisibleEverywhere(JavaClass type) {
        JavaClass base = type.isArray() ? type.getBaseComponentType() : type;
        if (base.isPrimitive()) {
            return true;
        }
        Optional<JavaClass> enclosing = base.getEnclosingClass();
        return base.getModifiers().contains(JavaModifier.PUBLIC) && (enclosing.isEmpty() || isVisibleEverywhere(enclosing.get()));
    }

    private static ArchCondition<JavaClass> bindEachValueAsWhatItMeans() {
        return new ArchCondition<>("bind each value as what it means") {
            @Override
            public void check(JavaClass settings, ConditionEvents events) {
                checkSettingsFields(settings, events, new HashSet<>());
            }
        };
    }

    /** The record's own fields, then those of every record of the application it holds, a map's or list's included. */
    private static void checkSettingsFields(JavaClass settings, ConditionEvents events, Set<JavaClass> seen) {
        if (!seen.add(settings)) {
            return;
        }
        for (JavaField field : settings.getFields()) {
            if (field.getModifiers().contains(JavaModifier.STATIC)) {
                continue;
            }
            String type = field.getRawType().getName();
            if (NUMBERS.contains(type) && UNIT_IN_THE_NAME.matcher(field.getName()).matches()) {
                events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is a number with its unit in its name"));
            }
            if (type.equals(String.class.getName()) && TEXT_WITH_A_GRAMMAR.matcher(field.getName()).matches()) {
                events.add(SimpleConditionEvent.violated(field, field.getFullName() + " is text with a grammar"));
            }
            for (JavaClass held : field.getType().getAllInvolvedRawTypes()) {
                if (held.getPackageName().startsWith(APP) && held.isAssignableTo(Record.class)) {
                    checkSettingsFields(held, events, seen);
                }
            }
        }
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

    /** ArchUnit's NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING sees a Logger field only, never a {@code Logger.getLogger(...)} call. */
    @ArchTest
    static final ArchRule noJavaUtilLogging = noClasses().should().dependOnClassesThat().resideInAPackage("java.util.logging..")
            .because("every line goes through SLF4J and the house pattern, with its context (OPS-01)");

    @ArchTest
    static final ArchRule noFieldInjection = NO_CLASSES_SHOULD_USE_FIELD_INJECTION;

    @ArchTest
    static final ArchRule noGenericExceptions = NO_CLASSES_SHOULD_THROW_GENERIC_EXCEPTIONS;
}
