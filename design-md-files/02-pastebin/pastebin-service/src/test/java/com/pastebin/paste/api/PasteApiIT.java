package com.pastebin.paste.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
class PasteApiIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
            .withDatabaseName("pastebin_test")
            .withUsername("pastebin")
            .withPassword("pastebin");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379).toString());
        registry.add("pastebin.s3.endpoint", () -> "http://localhost:9000");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void createsAndReadsPublicPaste() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "content", "hello pastebin",
                "language", "plaintext",
                "expiryPolicy", "ONE_DAY",
                "accessLevel", "PUBLIC"
        ));

        String createResponse = mockMvc.perform(post("/api/v1/pastes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortKey").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortKey = objectMapper.readTree(createResponse).get("shortKey").asText();

        mockMvc.perform(get("/api/v1/pastes/{key}", shortKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("hello pastebin"));

        mockMvc.perform(get("/raw/{key}", shortKey))
                .andExpect(status().isOk())
                .andExpect(header().exists("Cache-Control"))
                .andExpect(header().string("Content-Type", "text/plain;charset=UTF-8"));
    }

    @Test
    void registerLoginAndCreatePrivatePaste() throws Exception {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        String registerPayload = objectMapper.writeValueAsString(Map.of(
                "email", email,
                "password", "password123",
                "displayName", "tester"
        ));

        String authResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerPayload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(authResponse).get("token").asText();

        String createPayload = objectMapper.writeValueAsString(Map.of(
                "content", "private snippet",
                "accessLevel", "PRIVATE",
                "expiryPolicy", "NEVER"
        ));

        String createResponse = mockMvc.perform(post("/api/v1/pastes")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String shortKey = objectMapper.readTree(createResponse).get("shortKey").asText();

        mockMvc.perform(get("/api/v1/pastes/{key}", shortKey))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/pastes/{key}", shortKey)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("private snippet"));
    }
}
