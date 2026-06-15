package io.nexuspay.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Mixed-concurrency simulation: interleaves payment creation, refunds, and reads
 * at a sustained rate to surface lock contention and connection-pool exhaustion
 * across the whole stack at once.
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). Standalone gatling module — never part of
 * the {@code test} gate. This is the closest black-box analogue to production
 * traffic mix: writes (payments + refunds) racing reads against shared ledger
 * accounts and the connection pool.</p>
 *
 * <p>Run: {@code ./gradlew gatlingRun -p gatling
 * -Dgatling.simulationClass=io.nexuspay.gatling.MixedConcurrencySimulation}.</p>
 */
public class MixedConcurrencySimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String API_KEY = System.getProperty("apiKey", "sk_test_load_test_key");
    private static final int DURATION_SEC = Integer.getInteger("durationSec", 300);

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .header("Authorization", "Bearer " + API_KEY);

    ScenarioBuilder paymentFlow = scenario("Mixed — Payments")
            .exec(
                    http("Create Payment")
                            .post("/v1/payments")
                            .header("Idempotency-Key", session -> "idem_" + java.util.UUID.randomUUID())
                            .body(StringBody("""
                                    {
                                      "amount": 5000,
                                      "currency": "USD",
                                      "customer_id": "cust_mixed",
                                      "payment_method_type": "card",
                                      "capture_method": "automatic",
                                      "description": "Mixed concurrency payment"
                                    }
                                    """))
                            .check(status().in(201, 422, 500))
            );

    ScenarioBuilder refundFlow = scenario("Mixed — Refunds")
            .exec(
                    http("Create Refund")
                            .post("/v1/refunds")
                            .header("Idempotency-Key", session -> "idem_refund_" + java.util.UUID.randomUUID())
                            .body(StringBody("""
                                    {
                                      "payment_id": "pay_mixed_target",
                                      "amount": 1000,
                                      "currency": "USD",
                                      "reason": "mixed concurrency refund"
                                    }
                                    """))
                            .check(status().in(200, 201, 202, 409, 422, 500))
            );

    ScenarioBuilder readFlow = scenario("Mixed — Reads")
            .exec(
                    http("Get Ledger Accounts")
                            .get("/v1/ledger/accounts")
                            .check(status().is(200))
            )
            .exec(
                    http("List Journal Entries")
                            .get("/v1/ledger/journal-entries?limit=25")
                            .check(status().is(200))
            );

    {
        setUp(
                paymentFlow.injectOpen(constantUsersPerSec(50).during(DURATION_SEC)),
                refundFlow.injectOpen(constantUsersPerSec(20).during(DURATION_SEC)),
                readFlow.injectOpen(constantUsersPerSec(50).during(DURATION_SEC))
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95.0).lt(2000),
                        // Reads must stay healthy even while writes contend.
                        details("Mixed — Reads").failedRequests().percent().lt(2.0),
                        global().failedRequests().percent().lt(10.0)
                );
    }
}
