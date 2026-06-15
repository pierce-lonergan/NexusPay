package io.nexuspay.gatling;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Read-heavy storm: hammers the dashboard read paths (ledger accounts +
 * approvals list + journal entries) at high request rate to stress the read
 * paths and the JDBC connection pool.
 *
 * <p>Part of the simulation / red-team environment (see
 * {@code docs/simulation/README.md}). Standalone gatling module — never part of
 * the {@code test} gate. Extends the read-heavy {@code dashboardFlow} of
 * {@link PaymentLoadSimulation} to a much higher sustained rate.</p>
 *
 * <p>Run: {@code ./gradlew gatlingRun -p gatling
 * -Dgatling.simulationClass=io.nexuspay.gatling.DashboardReadStormSimulation}.</p>
 */
public class DashboardReadStormSimulation extends Simulation {

    private static final String BASE_URL = System.getProperty("baseUrl", "http://localhost:8090");
    private static final String API_KEY = System.getProperty("apiKey", "sk_test_load_test_key");
    private static final int READ_RATE = Integer.getInteger("readRate", 200);
    private static final int DURATION_SEC = Integer.getInteger("durationSec", 180);

    HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .header("Authorization", "Bearer " + API_KEY);

    ScenarioBuilder readStorm = scenario("Dashboard Read Storm")
            .exec(
                    http("Get Ledger Accounts")
                            .get("/v1/ledger/accounts")
                            .check(status().is(200))
            )
            .exec(
                    http("List Journal Entries")
                            .get("/v1/ledger/journal-entries?limit=50")
                            .check(status().is(200))
            )
            .exec(
                    http("List Approvals")
                            .get("/v1/approvals")
                            .check(status().is(200))
            );

    {
        setUp(
                readStorm.injectOpen(
                        rampUsersPerSec(10).to(READ_RATE).during(30),
                        constantUsersPerSec(READ_RATE).during(DURATION_SEC)
                )
        ).protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95.0).lt(1500),
                        global().successfulRequests().percent().gt(95.0)
                );
    }
}
