package com.test.banking.core;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class BankingCoreIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void endToEnd_customerAccountDepositTransfer() throws Exception {
        String pan = validPan();

        String customerJson = """
                {
                  "firstName": "Raj",
                  "lastName": "Kumar",
                  "dateOfBirth": "1990-05-15",
                  "gender": "M",
                  "pan": "%s"
                }
                """.formatted(pan);

        String cifResponse = mockMvc.perform(post("/api/v1/customers")
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(customerJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"))
                .andReturn().getResponse().getContentAsString();

        String cifId = extract(cifResponse, "cifId");

        String openKey1 = UUID.randomUUID().toString();
        String accountJson = """
                {
                  "cifId": "%s",
                  "accountType": "SAVINGS",
                  "productCode": "SAV_BASIC",
                  "initialDeposit": 10000.00
                }
                """.formatted(cifId);

        String accountResponse = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", openKey1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentBalance").value(10000.00))
                .andReturn().getResponse().getContentAsString();

        String accountId = extract(accountResponse, "accountId");

        String depositKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/transactions/deposit")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", depositKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "amount": 5000.00,
                                  "currency": "INR",
                                  "narration": "NEFT deposit stub"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        mockMvc.perform(post("/api/v1/transactions/deposit")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", depositKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "accountId": "%s",
                                  "amount": 5000.00,
                                  "currency": "INR",
                                  "narration": "NEFT deposit stub"
                                }
                                """.formatted(accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        String openKey2 = UUID.randomUUID().toString();
        String account2Response = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", openKey2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String accountId2 = extract(account2Response, "accountId");

        String transferKey = UUID.randomUUID().toString();
        mockMvc.perform(post("/api/v1/transactions/transfer")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", transferKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "fromAccountId": "%s",
                                  "toAccountId": "%s",
                                  "amount": 2000.00,
                                  "currency": "INR",
                                  "narration": "Internal transfer"
                                }
                                """.formatted(accountId, accountId2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("POSTED"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/transactions", accountId)
                        .with(user("teller").roles("TELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactions.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.transactions[0].runningBalance").exists());
    }

    @Test
    void customerCannotCreateCustomer() throws Exception {
        String pan = validPan();
        mockMvc.perform(post("/api/v1/customers")
                        .with(user("CIF-99999999").roles("CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"X","lastName":"Y","dateOfBirth":"1990-01-01","pan":"%s"}
                                """.formatted(pan)))
                .andExpect(status().isForbidden());
    }

    @Test
    void statementRejectsInvalidDateRange() throws Exception {
        String pan = validPan();
        String cifId = extract(mockMvc.perform(post("/api/v1/customers")
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"S","lastName":"T","dateOfBirth":"1990-01-01","pan":"%s"}
                                """.formatted(pan)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "cifId");

        String accountId = extract(mockMvc.perform(post("/api/v1/accounts")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cifId":"%s","accountType":"SAVINGS"}
                                """.formatted(cifId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "accountId");

        mockMvc.perform(post("/api/v1/accounts/{accountId}/statements/request", accountId)
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"fromDate":"2024-06-01","toDate":"2024-01-01","format":"JSON"}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void customerCannotAccessAnotherCustomersAccount() throws Exception {
        String pan1 = validPan();
        String pan2 = validPan();

        String cif1 = extract(mockMvc.perform(post("/api/v1/customers")
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"One","dateOfBirth":"1990-01-01","pan":"%s"}
                                """.formatted(pan1)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "cifId");

        String cif2 = extract(mockMvc.perform(post("/api/v1/customers")
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"B","lastName":"Two","dateOfBirth":"1991-02-02","pan":"%s"}
                                """.formatted(pan2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "cifId");

        String account2 = extract(mockMvc.perform(post("/api/v1/accounts")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cifId":"%s","accountType":"SAVINGS","initialDeposit":1000.00}
                                """.formatted(cif2)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "accountId");

        mockMvc.perform(get("/api/v1/accounts/{accountId}", account2)
                        .with(user(cif1).roles("CUSTOMER")))
                .andExpect(status().isForbidden());
    }

    private static String validPan() {
        return String.format("ABCDE%04dF", ThreadLocalRandom.current().nextInt(1000, 9999));
    }

    private String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalStateException("Field not found: " + field);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
