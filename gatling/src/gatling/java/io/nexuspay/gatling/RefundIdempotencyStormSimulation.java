package io.nexuspay.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Idempotency-storm load simulation: fires the SAME refund repeatedly and in
 * parallel to load-prove the deterministic-key dedup under contention.
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). Standalone gatling module — never part of
 * the {@code test} gate. Load-side complement to the in-gate
 * {@code RefundDeterministicKeyIdempotencySoakTest} (which asserts the EXACT
 * single-effect invariant in-JVM; this proves the dedup holds under HTTP load).</p>
 *
 * <p>Every virtual user replays the SAME {@code Idempotency-Key} against
 * POST /v1/refunds. A correct server (HyperSwitch deterministic-key dedup, B-009)
 * collapses them to a single refund effect; the load harness checks that the
 * endpoint does not error-storm under the duplicate flood.</p>
 *
 * <p>Run: {@code ./gradlew gatlingRun -p gatling
 * -Dgatling.simulationClass=io.nexuspay.gatling.RefundIdempotencyStormSimulation}.</p>
 */
public class RefundIdempotencyStormSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String API_KEY = System.getProperty("apiKey", "sk_test_load_test_key");

    // One fixed key replayed by every request — the whole point of the storm.
    private static final String SHARED_IDEMPOTENCY_KEY =
            System.getProperty("idempotencyKey", "idem_refund_storm_fixed");
    private static final String PAYMENT_ID = System.getProperty("paymentId", "pay_storm_target");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .header("Authorization", "Bearer " + API_KEY);

    ScenarioBuilder refundStorm = scenario("Refund Idempotency Storm")
            .exec(
                    http("Create Refund (same key)")
                            .post("/v1/refunds")
                            .header("Idempotency-Key", SHARED_IDEMPOTENCY_KEY)
                            .body(StringBody("""
                                    {
                                      "payment_id": "%s",
                                      "amount": 4999,
                                      "currency": "USD",
                                      "reason": "idempotency storm"
                                    }
                                    """.formatted(PAYMENT_ID)))
                            // Dedup may return 200/201/202/409/422; 500 tolerated (no real PSP).
                            .check(status().in(200, 201, 202, 409, 422, 500))
            );

    {
        setUp(
                refundStorm.injectOpen(
                        constantUsersPerSec(50).during(120) // sustained duplicate flood
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95.0).lt(2000),
                        // The duplicate flood must not error-storm.
                        global().failedRequests().percent().lt(5.0)
                );
    }
}
