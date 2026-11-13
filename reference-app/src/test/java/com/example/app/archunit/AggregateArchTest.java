package com.example.app.archunit;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;

import java.util.List;
import java.util.Optional;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

/**
 * A repository belongs to the service of its aggregate (ARCH-15): the aggregate root's service uses
 * the repositories of the root and of the entities inside the aggregate, and enforces their rules;
 * everything else, other services and Manager services included, goes through that service. Going
 * around it would skip its validation, ownership checks, locks and (later) its cache.
 */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class AggregateArchTest {

    // The review profile's `aggregates`. crud-gen adds a row for every new aggregate root, and puts an
    // entity inside an aggregate into its root's `members` (that entity gets no service of its own).
    private static final List<Aggregate> AGGREGATES = List.of(
            new Aggregate("User", "UserService", List.of()),
            // example:begin
            new Aggregate("TodoList", "TodoListService", List.of()),
            new Aggregate("Todo", "TodoService", List.of("TimeEntry")),
            new Aggregate("Tag", "TagService", List.of()),
            // example:end
            new Aggregate("RefreshToken", "TokenService", List.of()));

    private static final String REPOSITORY_SUFFIX = "Repository";
    private static final List<String> OWN_REPOSITORY_SUBPACKAGES = List.of(".impl", ".custom");

    private record Aggregate(String root, String service, List<String> members) {

        boolean contains(String entity) {
            return root.equals(entity) || members.contains(entity);
        }
    }

    @ArchTest
    static final ArchRule repositoriesAreUsedOnlyByTheirAggregateService = classes()
            .that().resideInAPackage("..repository")
            .and().areInterfaces()
            .and().haveSimpleNameEndingWith(REPOSITORY_SUFFIX)
            .should(beUsedOnlyByTheirAggregateService())
            .because("another aggregate is reached through its root's service, which owns its rules (ARCH-15)");

    private static ArchCondition<JavaClass> beUsedOnlyByTheirAggregateService() {
        return new ArchCondition<>("be used only by the service of their aggregate") {
            @Override
            public void check(JavaClass repository, ConditionEvents events) {
                String entity = repository.getSimpleName().substring(0,
                        repository.getSimpleName().length() - REPOSITORY_SUFFIX.length());
                Optional<Aggregate> aggregate = AGGREGATES.stream()
                        .filter(candidate -> candidate.contains(entity))
                        .findFirst();
                if (aggregate.isEmpty()) {
                    events.add(SimpleConditionEvent.violated(repository,
                            "%s belongs to no aggregate: add %s to AggregateArchTest.AGGREGATES"
                                    .formatted(repository.getSimpleName(), entity)));
                    return;
                }
                for (Dependency dependency : repository.getDirectDependenciesToSelf()) {
                    checkUser(dependency, aggregate.get(), events);
                }
            }
        };
    }

    private static void checkUser(Dependency dependency, Aggregate aggregate, ConditionEvents events) {
        JavaClass user = dependency.getOriginClass().getBaseComponentType();
        String topLevelName = user.getName().replaceAll("\\$.*", "");
        boolean ownPersistenceCode = isOwnRepositoryPackage(user.getPackageName(),
                dependency.getTargetClass().getBaseComponentType().getPackageName());
        boolean aggregateService = topLevelName.endsWith("." + aggregate.service());
        if (!ownPersistenceCode && !aggregateService) {
            events.add(SimpleConditionEvent.violated(dependency,
                    "%s is used by %s, but only %s may use it: call %s instead"
                            .formatted(dependency.getTargetClass().getSimpleName(), topLevelName,
                                    aggregate.service(), aggregate.service())));
        }
    }

    /** The repository's own package and its fragments' ({@code impl}, {@code custom}): not another module's. */
    private static boolean isOwnRepositoryPackage(String userPackage, String repositoryPackage) {
        if (!userPackage.startsWith(repositoryPackage)) {
            return false;
        }
        String subpackage = userPackage.substring(repositoryPackage.length());
        return subpackage.isEmpty() || OWN_REPOSITORY_SUBPACKAGES.contains(subpackage);
    }
}
