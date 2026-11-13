package com.example.app.archunit;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/** Layer direction inside each module: controller → service → repository → domain (ARCH-03, ARCH-11), and who may use repositories. */
@AnalyzeClasses(packages = "com.example.app", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchTest {

    @ArchTest
    static final ArchRule repositoriesDontCallUp = noClasses().that().resideInAPackage("..repository..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..")
            .because("repositories are called by services, never the reverse (ARCH-03)");

    @ArchTest
    static final ArchRule domainIsIndependent = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..service..", "..controller..", "..repository..", "..dto..", "..mapper..")
            .because("entities depend on no layer above them (ARCH-03)");

    @ArchTest
    static final ArchRule servicesDontKnowControllers = noClasses().that().resideInAPackage("..service..")
            .should().dependOnClassesThat().resideInAPackage("..controller..")
            .because("services are reusable outside HTTP (ARCH-03)");

    @ArchTest
    static final ArchRule controllersArePackagePrivateAdapters = classes().that().areAnnotatedWith(RestController.class)
            .should().notBePublic()
            .andShould().resideInAPackage("..controller")
            .because("controllers are package-private HTTP adapters (ARCH-11)");

    @ArchTest
    static final ArchRule controllersUseNoRepositoryOrEntity = noClasses().that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAnyPackage("..repository..", "..domain..")
            .because("controllers talk to services and DTOs only; entities never leave the service layer (ARCH-11)");

    @ArchTest
    static final ArchRule controllersAreNotTransactional = noClasses().that().areAnnotatedWith(RestController.class)
            .should().beAnnotatedWith(Transactional.class)
            .because("a class-level transaction on a controller holds a connection through (de)serialisation of every endpoint (ARCH-11)");

    @ArchTest
    static final ArchRule onlyServicesUseRepositories = classes().that().resideInAPackage("..repository..")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage("..service..", "..repository..")
            .because("repositoryAccess is aggregate-service: services only, and AggregateArchTest names which (ARCH-03, ARCH-15)");

    @ArchTest
    static final ArchRule controllersOpenNoTransactions = noMethods().that().areDeclaredInClassesThat().resideInAPackage("..controller..")
            .should().beAnnotatedWith(Transactional.class)
            .because("a transaction on a controller holds a connection through (de)serialisation (ARCH-11)");
}
