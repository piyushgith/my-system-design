package com.test.banking.core.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LienApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tellerCanPlaceAndReleaseLien() throws Exception {
        String pan = String.format("ABCDE%04dF", ThreadLocalRandom.current().nextInt(1000, 9999));
        String cifResponse = mockMvc.perform(post("/api/v1/customers")
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"L","lastName":"User","dateOfBirth":"1990-01-01","pan":"%s"}
                                """.formatted(pan)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String cifId = extract(cifResponse, "cifId");

        String accountResponse = mockMvc.perform(post("/api/v1/accounts")
                        .with(user("teller").roles("TELLER"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cifId":"%s","accountType":"SAVINGS","initialDeposit":10000.00}
                                """.formatted(cifId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String accountId = extract(accountResponse, "accountId");

        String lienResponse = mockMvc.perform(post("/api/v1/accounts/{accountId}/liens", accountId)
                        .with(user("teller").roles("TELLER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":3000.00,"reason":"Cheque hold","lienType":"CHEQUE_HOLD"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();

        String lienId = extractUuid(lienResponse, "lienId");

        mockMvc.perform(get("/api/v1/accounts/{accountId}/balance", accountId)
                        .with(user("teller").roles("TELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance", is(7000.00)));

        mockMvc.perform(post("/api/v1/accounts/{accountId}/liens/{lienId}/release", accountId, lienId)
                        .with(user("teller").roles("TELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"));

        mockMvc.perform(get("/api/v1/accounts/{accountId}/balance", accountId)
                        .with(user("teller").roles("TELLER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableBalance", is(10000.00)));
    }

    private String extract(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker) + marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }

    private String extractUuid(String json, String field) {
        String marker = "\"" + field + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            marker = "\"" + field + "\":";
            start = json.indexOf(marker) + marker.length();
            int end = json.indexOf(',', start);
            if (end < 0) {
                end = json.indexOf('}', start);
            }
            return json.substring(start, end).trim().replace("\"", "");
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        return json.substring(start, end);
    }
}
