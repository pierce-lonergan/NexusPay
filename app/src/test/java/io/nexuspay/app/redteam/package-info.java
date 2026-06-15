/**
 * RED-TEAM attack suite (REPORT-ONLY) — part of the simulation / red-team
 * environment (see {@code docs/simulation/README.md}).
 *
 * <p>Every class here carries {@code @Tag("redteam")} and asserts the SECURE
 * behavior the platform SHOULD have. Current {@code main} still has the holes the
 * SEC-* PRs fix, so the ACTIVE tests FAIL on main today — they are therefore EXCLUDED
 * from the default {@code ./gradlew test} gate (root {@code build.gradle.kts}
 * {@code useJUnitPlatform { excludeTags("redteam", "simulation") }}) and run only
 * by the report-only {@code redteamTest} task / the {@code redteam-sim}
 * GitHub Actions job (continue-on-error, {@code || echo ::warning}).</p>
 *
 * <p><strong>Invariant:</strong> no class here asserts insecure or non-existent
 * behavior, and none is GREEN on the vulnerable main (that would be false assurance).
 * Where a genuine fail-on-main assertion is not feasible in this harness
 * ({@code IdempotencyReuseRedteamTest}, {@code PaymentLifecycleIdorRedteamTest} —
 * both need a stub PSP the app harness does not yet have), the class is
 * {@code @Disabled} with a precise TODO instead of asserting a vacuous/false claim.
 * See {@code docs/simulation/README.md} for the full active/@Disabled/removed split.</p>
 *
 * <p>They still COMPILE under root {@code ./gradlew build} (the test compile task
 * builds them even though the tag filter removes them from execution), so CI
 * verifies buildability. As each SEC-* PR lands the fix, drop the
 * {@code @Tag("redteam")} on the now-green class to flip it into the gate — the
 * flip plan is in {@code docs/simulation/README.md}.</p>
 *
 * <p>These reuse {@code IntegrationTestBase} (Testcontainers Postgres/Kafka/
 * Valkey) and {@code TestSecurityConfig} (including the cross-tenant
 * {@code authFor(tenant, role)} overload), so they self-SKIP where Docker is
 * absent rather than failing — acceptable for a report-only job.</p>
 */
package io.nexuspay.app.redteam;
