package com.test.banking.core.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TellerAuthenticationTest {

    @Autowired
    private UserDetailsService userDetailsService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void tellerPasswordMatchesEncodedHash() {
        var user = userDetailsService.loadUserByUsername("teller");
        assertTrue(passwordEncoder.matches("teller", user.getPassword()));
    }

    @Test
    void referenceEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/reference/ifsc/HDFC0001234/validate"))
                .andExpect(status().isOk());
    }

    @Test
    void customersRejectWithoutCredentials() throws Exception {
        mockMvc.perform(post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","dateOfBirth":"1990-01-01","pan":"ABCDE1234F"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void customersAcceptTellerHttpBasic() throws Exception {
        String pan = String.format("ABCDE%04dF", 4321);
        mockMvc.perform(post("/api/v1/customers")
                        .with(httpBasic("teller", "teller"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "firstName": "Raj",
                                  "lastName": "Kumar",
                                  "dateOfBirth": "1990-05-15",
                                  "gender": "M",
                                  "pan": "%s"
                                }
                                """.formatted(pan)))
                .andExpect(status().isCreated());
    }
}
