package com.reconcile.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's clock.
 *
 * <p>One bean, injected everywhere, rather than {@code Instant.now()} at each call site. Three
 * things depend on it: webhook freshness windows, idempotency expiry, and payment expiry — all of
 * which are impossible to test at the boundaries that matter without being able to move time.
 * Every timestamp this system writes is UTC; the clock's zone is what makes that true rather than a
 * convention someone has to remember.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    public Clock utcClock() {
        return Clock.systemUTC();
    }
}