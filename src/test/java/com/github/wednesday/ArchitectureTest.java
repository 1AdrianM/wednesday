package com.github.wednesday;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureTest {

    static final String BASE = "com.github.wednesday";
    static final List<String> COMPONENTS =
            List.of("gateway", "stt", "ai", "context", "memory", "tools", "tts", "session");

    /** A component's provider adapters ({@code <component>.provider..}) are private to that component. */
    static ArchRule providerTypesStayInsideTheirComponent() {
        CompositeArchRule rule = null;
        for (String c : COMPONENTS) {
            ArchRule perComponent = noClasses()
                    .that().resideOutsideOfPackage(BASE + "." + c + "..")
                    .should().dependOnClassesThat().resideInAPackage(BASE + "." + c + ".provider..")
                    .as("no class outside '" + c + "' may use " + c + ".provider types")
                    .allowEmptyShould(true);
            rule = rule == null ? CompositeArchRule.of(perComponent) : rule.and(perComponent);
        }
        return rule;
    }

    /** Shared primitives must not depend on any component. */
    static ArchRule commonDependsOnNoComponent() {
        return noClasses().that().resideInAPackage(BASE + ".common..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        COMPONENTS.stream().map(c -> BASE + "." + c + "..").toArray(String[]::new))
                .allowEmptyShould(true);
    }

    private static JavaClasses mainClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(BASE);
    }

    @Test
    void componentPackagesExist() {
        JavaClasses classes = mainClasses();
        for (String c : COMPONENTS) {
            assertTrue(classes.containPackage(BASE + "." + c), "missing component package: " + c);
        }
    }

    @Test
    void mainCodeRespectsProviderBoundaries() {
        providerTypesStayInsideTheirComponent().check(mainClasses());
    }

    @Test
    void commonIsIndependentOfComponents() {
        commonDependsOnNoComponent().check(mainClasses());
    }

    @Test
    void ruleCatchesCrossComponentProviderImport() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                com.github.wednesday.ai.ArchFixtureLeak.class,
                com.github.wednesday.tts.provider.archfixture.FixtureVendorClient.class);
        assertTrue(providerTypesStayInsideTheirComponent().evaluate(fixture).hasViolation(),
                "an ai class using a tts.provider type must be reported");
    }

    @Test
    void ruleAllowsProviderUseInsideOwnComponent() {
        JavaClasses fixture = new ClassFileImporter().importClasses(
                com.github.wednesday.tts.ArchFixtureOwnUse.class,
                com.github.wednesday.tts.provider.archfixture.FixtureVendorClient.class);
        assertFalse(providerTypesStayInsideTheirComponent().evaluate(fixture).hasViolation());
    }
}
