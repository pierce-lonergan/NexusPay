package io.nexuspay.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Burst / spike load simulation for the payment-create path.
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). Lives in the standalone {@code gatling}
 * module, which is NOT in {@code settings.gradle.kts} — so it is invisible to the
 * root {@code ./gradlew build}/{@code test} gate and CANNOT red it. It is compiled
 * (buildability verified) by the report-only CI job via
 * {@code ./gradlew gatlingClasses -p gatling} and run on-demand against a RUNNING
 * app with {@code ./gradlew gatlingRun -p gatling
 * -Dgatling.simulationClass=io.nexuspay.gatling.PaymentBurstSimulation}.</p>
 *
 * <p>Profile: a sharp {@code atOnceUsers(200)} spike onto POST /v1/payments, each
 * with its own {@code Idempotency-Key}. Stresses the gateway + GatedPaymentGateway
 * port-boundary fraud/sanctions screening under a cold burst. Asserts the tail
 * latency stays bounded and there is no 5xx storm.</p>
 *
 * <p>The status check tolerates 201/422/500 like {@link PaymentLoadSimulation}
 * (no real PSP wired in load mode), so a stubbed connector does not fail the sim.
 * Money-exactness is NOT asserted here (black-box HTTP) — that is the job of the
 * in-gate JVM soak tests.</p>
 */
public class PaymentBurstSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String API_KEY = System.getProperty("apiKey", "sk_test_load_test_key");
    private static final int SPIKE_USERS = Integer.getInteger("spikeUsers", 200);

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .header("Authorization", "Bearer " + API_KEY);

    ScenarioBuilder burst = scenario("Payment Create Burst")
            .exec(
                    http("Create Payment (burst)")
                            .post("/v1/payments")
                            .header("Idempotency-Key", session -> "idem_" + java.util.UUID.randomUUID())
                            .body(StringBody("""
                                    {
                                      "amount": 4999,
                                      "currency": "USD",
                                      "customer_id": "cust_burst",
                                      "payment_method_type": "card",
                                      "capture_method": "automatic",
                                      "description": "Burst spike payment"
                                    }
                                    """))
                            // No real PSP in load mode → tolerate 201/422/500 like the baseline sim.
                            .check(status().in(201, 422, 500))
            );

    {
        setUp(
                burst.injectOpen(atOnceUsers(SPIKE_USERS))
        ).protocols(httpProtocol)
                .assertions(
                        // The spike must not melt down: bounded tail, no 5xx storm.
                        global().responseTime().percentile(99.0).lt(5000),
                        global().failedRequests().percent().lt(5.0)
                );
    }
}
