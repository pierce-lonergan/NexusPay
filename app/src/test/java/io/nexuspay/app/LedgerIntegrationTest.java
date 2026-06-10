package io.nexuspay.app;

import io.nexuspay.app.config.TestSecurityConfig;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand;
import io.nexuspay.ledger.application.CreateJournalEntryUseCase.CreateJournalEntryCommand.PostingLine;
import io.nexuspay.ledger.application.GetBalanceUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the ledger module through the gateway API.
 */
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
class LedgerIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CreateJournalEntryUseCase createJournalEntry;

    @Autowired
    private GetBalanceUseCase getBalance;

    @Test
    @DisplayName("Ledger accounts seeded by Flyway are accessible via API")
    void ledgerAccounts_seededByFlyway_accessibleViaApi() throws Exception {
        mockMvc.perform(get("/v1/ledger/accounts")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].currency").value("USD"));
    }

    @Test
    @DisplayName("Journal entry creates balanced posting accessible via API")
    void journalEntry_balancedPosting_accessibleViaApi() throws Exception {
        // Create a journal entry directly via use case
        var command = new CreateJournalEntryCommand(
                "pi_test_ledger_integ",
                "Test payment capture",
                "default",
                Map.of("test", "true"),
                List.of(
                        new PostingLine("la_merchant_recv_usd", 10000, "USD"),
                        new PostingLine("la_customer_liab_usd", -10000, "USD")
                )
        );
        var entry = createJournalEntry.execute(command);
        assertThat(entry).isNotNull();
        assertThat(entry.getPostings()).hasSize(2);

        // Verify via API
        mockMvc.perform(get("/v1/ledger/journal-entries")
                        .param("payment_reference", "pi_test_ledger_integ")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                TestSecurityConfig.authForRole("admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].payment_reference").value("pi_test_ledger_integ"))
                .andExpect(jsonPath("$[0].postings").isArray())
                .andExpect(jsonPath("$[0].postings.length()").value(2));
    }
}
