package com.example.app.archunit;

import com.example.app.common.tenancy.TenantScope;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
// tenancy-schema:begin
import org.springframework.data.jpa.repository.Query;
// tenancy-schema:end
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
// tenancy-schema:begin
import java.lang.reflect.Method;
// tenancy-schema:end
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
// tenancy-schema:begin
import java.util.regex.Pattern;
// tenancy-schema:end
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The tenancy rules no review should have to catch (house-review {@code references/tenancy.md}): the
 * tenant comes from the token only, one hook tells the database, one scope switches it, and the two
 * planes stay apart. The SQL rules read the sources, since ArchUnit can't see string constants.
 */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class TenancyArchTest {

    private static final String TENANCY = "com.example.app.common.tenancy";
    private static final String PLATFORM_PATH = "/api/{version}/platform/";
    /**
     * The control plane's services: the tenant module's, and in other modules those named
     * {@code Platform*} (the staff login, a direct cross-tenant edit) and the impersonation.
     */
    private static final String TENANT_SERVICES = "com.example.app.tenant.service";
    private static final String PLATFORM_SERVICE_PREFIX = "Platform";
    private static final String IMPERSONATION_SERVICE = "ImpersonationService";
    /** With tenancy only staff suspend accounts (global ones): a tenant-plane controller never reaches it. */
    private static final String ACCOUNT_STATUS_SERVICE = "AccountStatusService";
    /** The one request that names a tenant: which of the caller's own memberships to switch to. */
    private static final Set<String> TENANT_NAMING_REQUESTS = Set.of("SwitchTenantRequest");
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    @ArchTest
    static final ArchRule onlyTheTenancyPackageImplementsHibernatesHooks = noClasses().that().resideOutsideOfPackage(TENANCY)
            .should().implement(CurrentTenantIdentifierResolver.class)
            .orShould().implement(MultiTenantConnectionProvider.class)
            .because("one resolver reads the token's tenant and one hook tells the database: a second of either is a second, unreviewed way in (TEN-03, TEN-04)");

    @ArchTest
    static final ArchRule controllersNeverSwitchTheTenant = noClasses().that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAssignableTo(TenantScope.class)
            .because("a request's tenant is its token's; switching it on a request path is a cross-tenant read or write (TEN-02)");

    @ArchTest
    static final ArchRule requestRecordsCarryNoTenant = classes().that().resideInAPackage("..dto.request..")
            .and().haveSimpleNameNotEndingWith("Filter")
            .should(haveNoTenantField())
            .because("the tenant comes from the verified token, never from a body (TEN-01)");

    @ArchTest
    static final ArchRule filtersCarryNoTenant = classes().that().resideInAPackage("..dto.request..")
            .and().haveSimpleNameEndingWith("Filter")
            .should(haveNoTenantField())
            .because("the tenant comes from the verified token, never from a query parameter (TEN-01)");

    @ArchTest
    static final ArchRule tenantPlaneControllersTakeNoTenant = classes().that(areTenantPlaneControllers())
            .should(takeNoTenantParameter())
            .because("tenant-plane URLs carry no tenant: a path or parameter tenant is an id-guessing surface (TEN-01)");

    @ArchTest
    static final ArchRule tenantPlaneControllersDontUseThePlatform = noClasses().that(areTenantPlaneControllers())
            .should().dependOnClassesThat(areControlPlaneServices())
            .because("the tenant plane reaches the register only through tenant.api, never its staff operations (TEN-07)");

    @ArchTest
    static final ArchRule platformControllersUseOnlyThePlatform = noClasses().that(arePlatformControllers())
            .should().dependOnClassesThat(areTenantPlaneServices())
            .because("staff reach a tenant's data only through a Platform* service that audits and scopes the call (TEN-07)");

    // tenancy-schema:begin
    @Test
    @DisplayName("Native queries never name a schema: the hook's search_path decides it (TEN-21)")
    void nativeQueriesNameNoSchema() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String className = MAIN_SOURCES.relativize(source).toString().replace('/', '.').replaceAll("\\.java$", "");
            Class<?> type = Class.forName(className);
            for (Method method : type.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (Objects.nonNull(query) && query.nativeQuery() && namesASchema(query.value())) {
                    offenders.add(type.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    @DisplayName("Only the hook and the migrator write search_path, and no other code names a schema in SQL (TEN-20, TEN-21)")
    void onlyTheHookSetsTheSchema() throws Exception {
        Set<String> allowed = Set.of("TransactionLocalSchemaProvider.java", "TenantSchemaMigrator.java", "TenantSchemas.java");
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            if (allowed.contains(source.getFileName().toString())) {
                continue;
            }
            for (String line : Files.readAllLines(source)) {
                String code = line.strip();
                boolean comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//");
                if (!comment && (code.contains("search_path") || namesASchema(code))) {
                    offenders.add(source.getFileName() + ": " + code);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    /** {@code platform.}, {@code tenant_template.} or {@code t_<32 hex>.} inside SQL. */
    private static boolean namesASchema(String sql) {
        return Pattern.compile("\\b(platform|tenant_template|t_[0-9a-f]{32})\\.[a-z_]").matcher(sql).find();
    }

    // tenancy-schema:end
    // tenancy-row:begin
    @Test
    @DisplayName("Only the hook names app.tenant_id, the tenant every row-level security policy reads (TEN-04, TEN-31)")
    void onlyTheHookSetsTheTenant() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            if (source.getFileName().toString().equals("TransactionLocalTenantProvider.java")) {
                continue;
            }
            for (String line : Files.readAllLines(source)) {
                String code = line.strip();
                boolean comment = code.startsWith("*") || code.startsWith("/*") || code.startsWith("//");
                if (!comment && code.contains("app.tenant_id")) {
                    offenders.add(source.getFileName() + ": " + code);
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    // tenancy-row:end
    private static List<Path> mainSources() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> !path.getFileName().toString().equals("package-info.java"))
                    .toList();
        }
    }

    private static ArchCondition<JavaClass> haveNoTenantField() {
        return new ArchCondition<>("have no tenant field") {
            @Override
            public void check(JavaClass request, ConditionEvents events) {
                if (TENANT_NAMING_REQUESTS.contains(request.getSimpleName())) {
                    return;
                }
                for (JavaField field : request.getAllFields()) {
                    if (field.getName().toLowerCase(Locale.ROOT).contains("tenant")) {
                        events.add(SimpleConditionEvent.violated(field, field.getFullName() + " carries a tenant"));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> takeNoTenantParameter() {
        return new ArchCondition<>("take no tenant parameter") {
            @Override
            public void check(JavaClass controller, ConditionEvents events) {
                for (JavaMethod method : controller.getMethods()) {
                    for (Parameter parameter : method.reflect().getParameters()) {
                        String name = parameter.getName();
                        if (name.toLowerCase(Locale.ROOT).contains("tenant")) {
                            events.add(SimpleConditionEvent.violated(method, method.getFullName() + " takes " + name));
                        }
                    }
                }
            }
        };
    }

    private static boolean isPlatformController(JavaClass controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller.reflect(), RequestMapping.class);
        return Objects.nonNull(mapping) && mapping.path().length > 0 && mapping.path()[0].startsWith(PLATFORM_PATH);
    }

    private static DescribedPredicate<JavaClass> arePlatformControllers() {
        return new DescribedPredicate<>("are platform controllers") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(RestController.class) && isPlatformController(javaClass);
            }
        };
    }

    private static DescribedPredicate<JavaClass> areTenantPlaneControllers() {
        return new DescribedPredicate<>("are tenant-plane controllers") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.isAnnotatedWith(RestController.class) && !isPlatformController(javaClass);
            }
        };
    }

    private static boolean isControlPlaneService(JavaClass javaClass) {
        return javaClass.getPackageName().equals(TENANT_SERVICES) || javaClass.getSimpleName().startsWith(PLATFORM_SERVICE_PREFIX)
                || javaClass.getSimpleName().equals(IMPERSONATION_SERVICE) || javaClass.getSimpleName().equals(ACCOUNT_STATUS_SERVICE);
    }

    private static DescribedPredicate<JavaClass> areControlPlaneServices() {
        return new DescribedPredicate<>("are control-plane services") {
            @Override
            public boolean test(JavaClass javaClass) {
                return isControlPlaneService(javaClass);
            }
        };
    }

    private static DescribedPredicate<JavaClass> areTenantPlaneServices() {
        return new DescribedPredicate<>("are tenant-plane services") {
            @Override
            public boolean test(JavaClass javaClass) {
                return javaClass.getPackageName().endsWith(".service") && javaClass.getSimpleName().endsWith("Service")
                        && !isControlPlaneService(javaClass);
            }
        };
    }
}
