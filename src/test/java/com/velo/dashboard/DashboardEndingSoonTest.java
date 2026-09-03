package com.velo.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Дашборд, «подходят к концу»: порог считается от последнего отрезка продления, а не от всего срока. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DashboardEndingSoonTest {

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
    void extendedRentalEndingSoonCountedFromLastSegment() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String bike = extract(postJson(admin, "/api/assets",
                "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DB-EXT\",\"purchasePrice\":50000,"
                        + "\"purchaseAccountId\":\"" + account + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String customer = extract(postJson(admin, "/api/customers",
                "{\"fullName\":\"Продл Клиент\",\"phone\":\"+7 900 000-22-22\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // недельная аренда, выданная 34 дня назад (просрочена) и продленная на 5 недель
        // ОТ КОНЦА периода: конец = 34д-7д назад + 5 нед = now + 8 дней
        String rental = extract(postJson(admin, "/api/rentals",
                "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"week\","
                        + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":7000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + rental + "/issue",
                "{\"date\":\"" + Instant.now().minus(34, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/extend",
                "{\"duration\":5,\"durationUnit\":\"week\"}")
                .andExpect(status().isOk());

        // суммарный срок 6 недель, до конца 8 дней: от ВСЕГО срока это < 20% (ложное «подходит к концу»),
        // от последнего отрезка (5 недель продления) — 8/35 > 20%, уведомления быть не должно
        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endingSoon[?(@.id == '" + rental + "')]").doesNotExist())
                .andExpect(jsonPath("$.overdue[?(@.id == '" + rental + "')]").doesNotExist());
    }

    private String getJson(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, String body)
            throws Exception {
        return mvc.perform(post(url).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body == null ? "{}" : body));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return extract(result.getResponse().getContentAsString(), "accessToken");
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
