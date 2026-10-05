package com.reconcile;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Reconcile — Payment &amp; Settlement Reconciliation Engine.
 *
 * <p>A modular monolith by decision ADR-0001: one deployable, internally divided into
 * {@code ledger}, {@code payment}, {@code provider}, {@code reconciliation}, {@code audit} and
 * {@code idempotency}. The domain packages underneath those contain no Spring and no JPA, which
 * {@code DomainArchitectureTest} enforces rather than merely intending.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ReconcileApplication {

    public static void main(String[] args) {
        SpringApplication.run(ReconcileApplication.class, args);
    }
}