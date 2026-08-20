package com.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Proves the HTTP wiring end-to-end: real dispatch through
 * {@link LedgerConfiguration}'s beans, not a direct call into the core.
 * The exhaustive unhappy-path matrix belongs to task 6, per the plan.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsAccountsTransfersMoneyAndReadsBalancesThroughHttp() throws Exception {
        String fromAccountId = createAccount(1_000);
        String toAccountId = createAccount(0);

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new TransferRequest(fromAccountId, toAccountId, 400))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/accounts/{id}/balance", fromAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinorUnits").value(600));
        mockMvc.perform(get("/accounts/{id}/balance", toAccountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinorUnits").value(400));
    }

    @Test
    void repeatedIdempotencyKeyReturnsTheOriginalOutcomeWithoutApplyingTwice() throws Exception {
        String fromAccountId = createAccount(1_000);
        String toAccountId = createAccount(0);
        TransferRequest request = new TransferRequest(fromAccountId, toAccountId, 400);
        String idempotencyKey = UUID.randomUUID().toString();

        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        mockMvc.perform(get("/accounts/{id}/balance", fromAccountId))
                .andExpect(jsonPath("$.balanceMinorUnits").value(600)); // moved once, not twice
    }

    private String createAccount(long initialBalanceMinorUnits) throws Exception {
        String responseBody = mockMvc.perform(post("/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest(initialBalanceMinorUnits))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readValue(responseBody, AccountResponse.class).accountId();
    }
}
