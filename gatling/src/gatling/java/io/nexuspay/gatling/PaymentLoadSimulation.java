package io.nexuspay.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Minimal Gatling load test for NexusPay Phase 1 baseline.
 *
 * Target: > 200 TPS at p95 < 1000ms
 *
 * NOTE: Uses dummy connector — real PSP latency will be higher.
 * This tests NexusPay overhead only.
 *
 * Run with: ./gradlew gatlingRun
 * Or: ./gradlew gatlingRun -Dgatling.simulationClass=io.nexuspay.gatling.PaymentLoadSimulation
 */
public class PaymentLoadSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String API_KEY = System.getProperty("apiKey", "sk_test_load_test_key");

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .header("Authorization", "Bearer " + API_KEY);

    // Scenario: Create payment → Get payment → Get ledger accounts
    ScenarioBuilder paymentFlow = scenario("Payment Flow")
            .exec(
                    http("Create Payment")
                            .post("/v1/payments")
                            .header("Idempotency-Key", session -> "idem_" + java.util.UUID.randomUUID())
                            .body(StringBody("""
                                    {
                                      "amount": 5000,
                                      "currency": "USD",
                                      "customer_id": "cust_loadtest",
                                      "payment_method_type": "card",
                                      "capture_method": "automatic",
                                      "description": "Load test payment"
                                    }
                                    """))
                            .check(status().in(201, 422, 500))
            )
            .pause(1)
            .exec(
                    http("Get Ledger Accounts")
                            .get("/v1/ledger/accounts")
                            .check(status().is(200))
            )
            .pause(1)
            .exec(
                    http("List Approvals")
                            .get("/v1/approvals")
                            .check(status().is(200))
            );

    // Scenario: Read-heavy (simulates dashboard)
    ScenarioBuilder dashboardFlow = scenario("Dashboard Flow")
            .exec(
                    http("Get Ledger Accounts")
                            .get("/v1/ledger/accounts")
                            .check(status().is(200))
            )
            .pause(2)
            .exec(
                    http("List Journal Entries")
                            .get("/v1/ledger/journal-entries?limit=50")
                            .check(status().is(200))
            );

    {
        setUp(
                paymentFlow.injectOpen(
                        rampUsers(50).during(30),        // Ramp up 50 users over 30s
                        constantUsersPerSec(50).during(300) // Sustain 50 users/sec for 5 min
                ),
                dashboardFlow.injectOpen(
                        rampUsers(20).during(30),
                        constantUsersPerSec(10).during(300)
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95.0).lt(1000),
                        global().successfulRequests().percent().gt(95.0)
                );
    }
}
