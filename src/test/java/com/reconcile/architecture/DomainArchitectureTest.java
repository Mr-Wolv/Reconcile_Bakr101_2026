package com.reconcile.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;

/**
 * Maps to row {@code C-ARCH-01}.
 *
 * <p>The architecture rules in the specification are only worth anything if a test fails when they
 * are broken. An intention in a document decays; this does not.
 */
@AnalyzeClasses(packages = "com.reconcile", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {

    @ArchTest
    static final ArchRule domain_must_not_depend_on_spring =
            noClasses()
                    .that().resideInAnyPackage(
                            "com.reconcile.shared..",
                            "com.reconcile.ledger..",
                            "com.reconcile.payment..",
                            "com.reconcile.reconciliation..",
                            "com.reconcile.provider..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "org.springframework..",
                            "jakarta.persistence..",
                            "javax.persistence..")
                    .because("the domain owns the financial invariants and must stay testable in "
                            + "milliseconds with no container; persistence must not be able to "
                            + "bypass it");

    @ArchTest
    static final ArchRule ledger_must_not_depend_on_payment =
            noClasses()
                    .that().resideInAPackage("com.reconcile.ledger..")
                    .should().dependOnClassesThat().resideInAPackage("com.reconcile.payment..")
                    .because("the payment service asks the ledger to post a named transaction "
                            + "type; the ledger knows nothing about payment lifecycles");

    @ArchTest
    static final ArchRule shared_must_not_depend_on_anything_in_the_project =
            noClasses()
                    .that().resideInAPackage("com.reconcile.shared..")
                    // Listed explicitly rather than as "anything under com.reconcile", because a
                    // package-wide rule also matches shared's own internal references.
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "com.reconcile.ledger..",
                            "com.reconcile.payment..",
                            "com.reconcile.reconciliation..",
                            "com.reconcile.provider..")
                    .because("shared is the base of the dependency graph and must depend on nothing");

    /**
     * Records the rule set that must not grow silently; reviewed alongside this file.
     *
     * <p>A fourth rule used to sit here — "only ReconcileApplication is named ReconcileApplication"
     * — asserting that a class is itself. It could never fail, so it could never catch anything,
     * and the repository's own rule is that a check which cannot fail is worse than no check.
     * Deleted rather than replaced: the one thing worth asserting about the entry point is that it
     * is the only class carrying {@code @SpringBootApplication}, which is a different rule and
     * belongs with the tests that would notice a second one.
     */
    static final List<String> ENFORCED = List.of(
            "domain -> no Spring, no JPA",
            "ledger -> no payment",
            "shared -> no project dependencies");
}
