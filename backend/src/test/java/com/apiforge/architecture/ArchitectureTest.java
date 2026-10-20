package com.apiforge.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import jakarta.persistence.Entity;

import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Module and layering rules from docs/ARCHITECTURE.md section 3.1, checked on every build. */
@AnalyzeClasses(packages = "com.apiforge", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule controllersDoNotUseRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().resideInAPackage("..repository..")
            .because("controllers delegate to services, which own data access and authorization");

    @ArchTest
    static final ArchRule controllersDoNotExposeEntities = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .should().dependOnClassesThat().areAnnotatedWith(Entity.class)
            .because("the API returns DTOs, never persistence entities");

    @ArchTest
    static final ArchRule webLayerDoesNotUseRepositories = noClasses()
            .that().resideInAPackage("..web..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

    @ArchTest
    static final ArchRule repositoriesAreUsedOnlyByServices = classes()
            .that().resideInAPackage("..repository..")
            .should().onlyBeAccessed().byAnyPackage("..service..", "..repository..");

    @ArchTest
    static final ArchRule entitiesLiveInDomainPackages = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain..");

    /** common.web holds shared helpers (pagination, request metadata) that services may use. */
    @ArchTest
    static final ArchRule servicesDoNotDependOnControllersOrServletApi = noClasses()
            .that().resideInAPackage("..service..")
            .should().dependOnClassesThat(resideInAPackage("..web..").and(not(resideInAPackage("com.apiforge.common.web.."))))
            .orShould().dependOnClassesThat().resideInAPackage("jakarta.servlet..");

    @ArchTest
    static final ArchRule featureModulesAreFreeOfCycles = slices()
            .matching("com.apiforge.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule jackson2StaysOutUntilOpenApiAdaptersNeedIt = noClasses()
            .should().dependOnClassesThat().resideInAPackage("com.fasterxml.jackson.databind..")
            .because("the application uses Jackson 3; Jackson 2 is confined to OpenAPI library adapters (ADR-004)");
}
