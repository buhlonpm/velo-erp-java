package com.velo.gps;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Удаление SIM-карт и GPS-трекеров: доступно всем сотрудникам, только «неиспользованные»
 * записи (ACTIVE, не вставлены/не установлены); системная операция покупки стирается вместе.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DirectoryDeletionTest {

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
    void deleteTrackerRemovesPurchaseTransaction() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String tracker = createTracker(admin, "DelTeltonika FMB920");

        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/gps-trackers").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + tracker + "')]").isEmpty());
        // операция покупки стёрта вместе с трекером — сирот в финансах нет
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка GPS-трекера: DelTeltonika FMB920')]").isEmpty());
    }

    @Test
    void deleteSimCardRemovesPurchaseTransaction() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 700-00-01\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"2024-02-01T10:00:00Z\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(delete("/api/sim-cards/" + sim).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/sim-cards").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')]").isEmpty());
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка SIM-карты: +7 900 700-00-01')]").isEmpty());
    }

    @Test
    void managerCanDeleteUnusedTrackerAndSimCard() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cleaner@velo.local\",\"fullName\":\"Уборщик\","
                                + "\"password\":\"cleaner-pass-1\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated());
        String manager = login("cleaner@velo.local", "cleaner-pass-1");

        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String tracker = createTracker(admin, "DelCoban GPS303");
        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 700-00-02\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"2024-02-01T10:00:00Z\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // удаление больше не ADMIN-only: менеджер тоже может
        mvc.perform(delete("/api/sim-cards/" + sim).header("Authorization", "Bearer " + manager))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + manager))
                .andExpect(status().isNoContent());
    }

    @Test
    void writtenOffTrackerAndSimCardCannotBeDeleted() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String tracker = createTracker(admin, "DelXexun TK102");
        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 700-00-03\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"2024-02-01T10:00:00Z\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        postJson(admin, "/api/gps-trackers/" + tracker + "/write-off", "{\"reason\":\"broken\"}")
                .andExpect(status().isOk());
        postJson(admin, "/api/sim-cards/" + sim + "/write-off", "{\"reason\":\"lost\"}")
                .andExpect(status().isOk());

        // списанные — часть истории, удалить нельзя
        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/sim-cards/" + sim).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    void trackerWithBundledSimCardDeletionFlow() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String tracker = createTracker(admin, "DelConcox GT06N");
        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 700-00-04\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // трекер с симкой удалить нельзя — ни пока вставлена, ни после извлечения (комплектная связь)
        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        // вставленную симку тоже нельзя
        mvc.perform(delete("/api/sim-cards/" + sim).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        // извлекли симку из трекера — трекер всё равно 409 (связь «в комплекте» живая)
        mvc.perform(patch("/api/gps-trackers/" + tracker)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearSimCard\":true}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        // сначала симка (операции покупки у комплектной нет), потом трекер
        mvc.perform(delete("/api/sim-cards/" + sim).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    @Test
    void installedTrackerCannotBeDeleted() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"DEL-BIKE-1\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String tracker = createTracker(admin, "DelQueclink GV300");

        postJson(admin, "/api/assets/" + bike + "/tracker/" + tracker, null)
                .andExpect(status().isOk());

        mvc.perform(delete("/api/gps-trackers/" + tracker).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    private String createTracker(String token, String model) throws Exception {
        String account = extract(getJson(token, "/api/finance/accounts"), "id");
        return extract(postJson(token, "/api/gps-trackers",
                        "{\"model\":\"" + model + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\","
                                + "\"purchasePrice\":2500,\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
    }

    private ResultActions postJson(String token, String url, String body) throws Exception {
        var request = post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(body != null ? request.content(body) : request);
    }

    private String getJson(String token, String url) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
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
