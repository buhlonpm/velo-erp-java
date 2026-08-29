package com.velo.asset;

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

/** Журнал циклов перезарядки АКБ: история, кэш по последней дате, запрет уменьшения. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ChargeCycleLogTest {

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
    void chargeCycleLogKeepsHistoryAndNeverDecreases() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasePrice\":500,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String battery = extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-CC1\"" + purchase + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // циклы только у АКБ: велосипеду — 409
        String bike = extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-CC1\"" + purchase + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        recordCycles(admin, bike, "{\"cycles\":10}").andExpect(status().isConflict());

        // две записи: неделю назад и сейчас
        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        recordCycles(admin, battery, "{\"cycles\":100,\"recordedAt\":\"" + weekAgo + "\"}")
                .andExpect(status().isCreated());
        recordCycles(admin, battery, "{\"cycles\":150}").andExpect(status().isCreated());

        // текущее значение = последняя запись
        mvc.perform(get("/api/assets?type=battery").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].chargeCycles").value(150));

        // запись задним числом со значением МЕНЬШЕ текущего — 409: циклы не могут уменьшаться
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        recordCycles(admin, battery, "{\"cycles\":120,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isConflict());

        // задним числом, но НЕ меньше текущего — ок; текущее значение не меняется (оно по последней дате)
        recordCycles(admin, battery, "{\"cycles\":160,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isCreated());
        mvc.perform(get("/api/assets?type=battery").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].chargeCycles").value(150));

        // журнал отдаёт все записи, новые сверху
        mvc.perform(get("/api/assets/" + battery + "/charge-cycles")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].cycles").value(150));
    }

    private org.springframework.test.web.servlet.ResultActions recordCycles(String token, String assetId, String body)
            throws Exception {
        return mvc.perform(post("/api/assets/" + assetId + "/charge-cycles")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
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
