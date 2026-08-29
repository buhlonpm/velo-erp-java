package com.velo.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AuthFlowTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void adminIsSeededAndCanLogin() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.refreshToken").isString())
                .andExpect(jsonPath("$.user.email").value("admin@velo.local"))
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedEndpointRequiresToken() throws Exception {
        mvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void accessTokenGrantsAccessAndRefreshRotates() throws Exception {
        String loginBody = loginAndGetBody("admin@velo.local", "admin123");
        String accessToken = extract(loginBody, "accessToken");
        String refreshToken = extract(loginBody, "refreshToken");

        mvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].email").value("admin@velo.local"));

        MvcResult refreshResult = mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isString())
                .andReturn();

        // старый refresh-токен погашен ротацией
        mvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        String rotatedAccess = extract(refreshResult.getResponse().getContentAsString(), "accessToken");
        mvc.perform(get("/api/users")
                        .header("Authorization", "Bearer " + rotatedAccess))
                .andExpect(status().isOk());
    }

    @Test
    void deactivatedUserLosesAccessImmediately() throws Exception {
        String adminToken = extract(loginAndGetBody("admin@velo.local", "admin123"), "accessToken");

        // админ создаёт менеджера
        MvcResult created = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager@velo.local\",\"fullName\":\"Менеджер Тестовый\","
                                + "\"password\":\"manager-pass-1\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String managerId = extract(created.getResponse().getContentAsString(), "id");

        // менеджер логинится и ходит в API
        String managerToken = extract(loginAndGetBody("manager@velo.local", "manager-pass-1"), "accessToken");
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden()); // MANAGER не ADMIN

        // админ деактивирует менеджера — доступ пропадает мгновенно
        mvc.perform(patch("/api/users/" + managerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/users").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void permissionChangeKillsAccessTokenImmediately() throws Exception {
        String adminToken = extract(loginAndGetBody("admin@velo.local", "admin123"), "accessToken");

        MvcResult created = mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"manager2@velo.local\",\"fullName\":\"Менеджер Второй\","
                                + "\"password\":\"manager-pass-2\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String managerId = extract(created.getResponse().getContentAsString(), "id");

        String managerToken = extract(loginAndGetBody("manager2@velo.local", "manager-pass-2"), "accessToken");
        // токен валиден (403 — не ADMIN, но не 401)
        mvc.perform(get("/api/users").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isForbidden());

        // админ меняет набор прав — access-токен мгновенно мёртв
        mvc.perform(patch("/api/users/" + managerId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"permissions\":[\"finance:view\"]}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/users").header("Authorization", "Bearer " + managerToken))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetBody(String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("поле %s в ответе", field).isTrue();
        return matcher.group(1);
    }
}
