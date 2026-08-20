package com.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The unhappy-path matrix through real HTTP dispatch: proves the status
 * code mapping (not just the underlying business logic, already covered
 * by TransferServiceTest/IdempotentTransferServiceTest at the core
 * level) is wired correctly end-to-end.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LedgerApiErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void transferWithInsufficientFundsReturns409AndLeavesBalancesUnchanged() throws Exception {
        String from = createAccount(10);
        String to = createAccount(0);

        mockMvc.perform(postTransfer(newIdempotencyKey(), from, to, 1_000))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Insufficient funds"));

        mockMvc.perform(get("/accounts/{id}/balance", from))
                .andExpect(jsonPath("$.balanceMinorUnits").value(10));
        mockMvc.perform(get("/accounts/{id}/balance", to))
                .andExpect(jsonPath("$.balanceMinorUnits").value(0));
    }

    @Test
    void transferFromUnknownAccountReturns404() throws Exception {
        String to = createAccount(0);
        String unknownFrom = UUID.randomUUID().toString();

        mockMvc.perform(postTransfer(newIdempotencyKey(), unknownFrom, to, 10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found: " + unknownFrom));
    }

    @Test
    void transferToUnknownAccountReturns404() throws Exception {
        String from = createAccount(100);
        String unknownTo = UUID.randomUUID().toString();

        mockMvc.perform(postTransfer(newIdempotencyKey(), from, unknownTo, 10))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Account not found: " + unknownTo));
    }

    @Test
    void selfTransferReturns400() throws Exception {
        String account = createAccount(100);

        mockMvc.perform(postTransfer(newIdempotencyKey(), account, account, 10))
                .andExpect(status().isBadRequest());
    }

    @Test
    void zeroAmountTransferReturns400() throws Exception {
        String from = createAccount(100);
        String to = createAccount(0);

        mockMvc.perform(postTransfer(newIdempotencyKey(), from, to, 0))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeAmountTransferReturns400() throws Exception {
        String from = createAccount(100);
        String to = createAccount(0);

        mockMvc.perform(postTransfer(newIdempotencyKey(), from, to, -10))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingIdempotencyKeyHeaderReturns400() throws Exception {
        String from = createAccount(100);
        String to = createAccount(0);

        mockMvc.perform(post("/transfers")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new TransferRequest(from, to, 10))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reusingIdempotencyKeyWithDifferentParametersReturns409() throws Exception {
        String from = createAccount(100);
        String to = createAccount(0);
        String otherTo = createAccount(0);
        String idempotencyKey = newIdempotencyKey();

        mockMvc.perform(postTransfer(idempotencyKey, from, to, 10))
                .andExpect(status().isOk());

        mockMvc.perform(postTransfer(idempotencyKey, from, otherTo, 10))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "Idempotency key already used with different transfer parameters: " + idempotencyKey));
    }

    @Test
    void balanceForUnknownAccountReturns404() throws Exception {
        mockMvc.perform(get("/accounts/{id}/balance", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void creatingAccountWithNegativeInitialBalanceReturns400() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(new CreateAccountRequest(-1L))))
                .andExpect(status().isBadRequest());
    }

    private static String newIdempotencyKey() {
        // Not just hygiene: @SpringBootTest reuses the same context (and so the same
        // singleton IdempotentTransferService/in-flight map) across every test method
        // in this class and in LedgerApiIntegrationTest, so a hardcoded literal key
        // could silently collide with an unrelated test.
        return UUID.randomUUID().toString();
    }

    private MockHttpServletRequestBuilder postTransfer(String idempotencyKey, String from, String to, long amount)
            throws Exception {
        return post("/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new TransferRequest(from, to, amount)));
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
