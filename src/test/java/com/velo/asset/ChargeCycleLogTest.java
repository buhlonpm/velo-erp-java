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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

        // задним числом и НЕ меньше текущего — тоже 409: дата не может быть раньше последней записи
        recordCycles(admin, battery, "{\"cycles\":160,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isConflict());

        // будущая дата — 400
        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        recordCycles(admin, battery, "{\"cycles\":160,\"recordedAt\":\"" + tomorrow + "\"}")
                .andExpect(status().isBadRequest());

        // журнал отдаёт все записи, новые сверху
        mvc.perform(get("/api/assets/" + battery + "/charge-cycles")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].cycles").value(150));
    }

    @Test
    void chargeCycleEntryEditAndDelete() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasePrice\":500,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";
        String battery = extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-CC2\"" + purchase + "}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        recordCycles(admin, battery, "{\"cycles\":100,\"recordedAt\":\"" + weekAgo + "\"}")
                .andExpect(status().isCreated());
        recordCycles(admin, battery, "{\"cycles\":120,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isCreated());
        recordCycles(admin, battery, "{\"cycles\":150}").andExpect(status().isCreated());

        // журнал: новые сверху — [150, 120, 100]
        String log = mvc.perform(get("/api/assets/" + battery + "/charge-cycles")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        String latestId = extract(log, "id", 0);
        String middleId = extract(log, "id", 1);

        // правка средней записи в рамках соседей — ок + событие в ленту
        mvc.perform(patch("/api/assets/" + battery + "/charge-cycles/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cycles\":125}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycles").value(125));
        mvc.perform(get("/api/assets/" + battery + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[*].comment",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString(
                                "Циклы перезарядки изменены: циклы: 120 → 125"))));

        // правка средней записи выше более поздней — 409 (журнал монотонный)
        mvc.perform(patch("/api/assets/" + battery + "/charge-cycles/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cycles\":160}"))
                .andExpect(status().isConflict());
        // будущая дата при правке — 400
        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        mvc.perform(patch("/api/assets/" + battery + "/charge-cycles/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordedAt\":\"" + tomorrow + "\"}"))
                .andExpect(status().isBadRequest());

        // удаление последней записи — текущее значение пересчитано от оставшихся + событие
        mvc.perform(delete("/api/assets/" + battery + "/charge-cycles/" + latestId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/assets?type=battery").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].chargeCycles").value(125));
        mvc.perform(get("/api/assets/" + battery + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[*].comment",
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString(
                                "Циклы перезарядки удалены: 150"))));

        // удаление единственной оставшейся пары — пустой журнал → кэш null
        String rest = mvc.perform(get("/api/assets/" + battery + "/charge-cycles")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(delete("/api/assets/" + battery + "/charge-cycles/" + extract(rest, "id", 0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/assets/" + battery + "/charge-cycles/" + extract(rest, "id", 1))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/assets/" + battery + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.chargeCycles").value(org.hamcrest.Matchers.nullValue()));

        // чужая запись — 404
        mvc.perform(delete("/api/assets/" + battery + "/charge-cycles/" + latestId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
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
        return extract(json, field, 0);
    }

    /** n-е вхождение поля (0 — первое) — для списков. */
    private static String extract(String json, String field, int index) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        for (int i = 0; i <= index; i++) {
            assertThat(matcher.find()).isTrue();
            if (i == index) {
                return matcher.group(1);
            }
        }
        throw new IllegalStateException();
    }
}
