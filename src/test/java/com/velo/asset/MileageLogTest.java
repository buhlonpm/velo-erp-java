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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MileageLogTest {

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
    void mileageLogKeepsHistoryAndLatestByDate() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasePrice\":500,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-M1\",\"mileageKm\":1000" + purchase + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // у зарядников пробега нет
        String charger = extract(createAsset(admin,
                        "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-M1\"" + purchase + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        recordMileage(admin, charger, "{\"mileageKm\":10}").andExpect(status().isConflict());

        // две записи: неделю назад и сейчас
        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        recordMileage(admin, bike, "{\"mileageKm\":1200,\"recordedAt\":\"" + weekAgo + "\"}")
                .andExpect(status().isCreated());
        recordMileage(admin, bike, "{\"mileageKm\":1350}")
                .andExpect(status().isCreated());

        // текущий пробег = последняя запись
        mvc.perform(get("/api/assets?type=bike").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')].mileageKm").value(1350));

        // запись задним числом со значением МЕНЬШЕ текущего — 409: пробег не может уменьшаться
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        recordMileage(admin, bike, "{\"mileageKm\":1300,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isConflict());

        // задним числом и НЕ меньше текущего — тоже 409: дата не может быть раньше последней записи
        recordMileage(admin, bike, "{\"mileageKm\":1400,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isConflict());
        mvc.perform(get("/api/assets?type=bike").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')].mileageKm").value(1350));

        // журнал отдаёт все записи, новые сверху
        mvc.perform(get("/api/assets/" + bike + "/mileage")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mileageKm").value(1350));
    }

    @Test
    void batteryMileageIsManual() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasePrice\":500,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-BM1\",\"mileageKm\":1000" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String battery = extract(createAsset(admin,
                        "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-BM1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // монтаж — без указания пробега
        mvc.perform(post("/api/assets/" + battery + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // пробег велосипеда НЕ влияет на пробег АКБ — он только ручной
        recordMileage(admin, bike, "{\"mileageKm\":1250}").andExpect(status().isCreated());
        mvc.perform(get("/api/assets?type=battery").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].mileageKm").value(0));

        // ручная запись пробега АКБ
        recordMileage(admin, battery, "{\"mileageKm\":5300}").andExpect(status().isCreated());
        mvc.perform(get("/api/assets?type=battery").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].mileageKm").value(5300))
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')].bikeId").value(bike));

        // журнал АКБ: только ручная запись
        mvc.perform(get("/api/assets/" + battery + "/mileage").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].mileageKm").value(5300));
    }

    @Test
    void secondBatteryMountRejected() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasePrice\":500,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-SB1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String battery1 = extract(createAsset(admin,
                        "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-SB1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String battery2 = extract(createAsset(admin,
                        "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-SB2\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(post("/api/assets/" + battery1 + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // вторая АКБ на тот же велосипед — 409
        mvc.perform(post("/api/assets/" + battery2 + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        // демонтировали первую — вторая монтируется
        mvc.perform(delete("/api/assets/" + battery1 + "/mount")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(post("/api/assets/" + battery2 + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void mileageEntryEditAndDelete() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-ME1\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + accountId
                                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        String weekAgo = Instant.now().minus(7, ChronoUnit.DAYS).toString();
        String yesterday = Instant.now().minus(1, ChronoUnit.DAYS).toString();
        recordMileage(admin, bike, "{\"mileageKm\":1000,\"recordedAt\":\"" + weekAgo + "\"}")
                .andExpect(status().isCreated());
        recordMileage(admin, bike, "{\"mileageKm\":1200,\"recordedAt\":\"" + yesterday + "\"}")
                .andExpect(status().isCreated());
        recordMileage(admin, bike, "{\"mileageKm\":1500}").andExpect(status().isCreated());

        // журнал: новые сверху — [1500, 1200, 1000]
        String log = mvc.perform(get("/api/assets/" + bike + "/mileage")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        String latestId = extract(log, "id", 0);
        String middleId = extract(log, "id", 1);

        // правка средней записи в рамках соседей — ок + событие в ленту
        mvc.perform(patch("/api/assets/" + bike + "/mileage/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mileageKm\":1250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mileageKm").value(1250));
        mvc.perform(get("/api/assets/" + bike + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[*].comment",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Пробег изменён: пробег: 1200 км → 1250 км"))));

        // правка средней записи выше более поздней — 409 (журнал монотонный)
        mvc.perform(patch("/api/assets/" + bike + "/mileage/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mileageKm\":1600}"))
                .andExpect(status().isConflict());
        // и ниже более ранней — тоже 409
        mvc.perform(patch("/api/assets/" + bike + "/mileage/" + middleId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mileageKm\":900}"))
                .andExpect(status().isConflict());

        // удаление последней записи — текущий пробег пересчитан от оставшихся + событие
        mvc.perform(delete("/api/assets/" + bike + "/mileage/" + latestId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/assets?type=bike").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')].mileageKm").value(1250));
        mvc.perform(get("/api/assets/" + bike + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[*].comment",
                        org.hamcrest.Matchers.hasItem(
                                org.hamcrest.Matchers.containsString("Пробег удалён: 1500 км"))))
                // автор события пробега заполнен
                .andExpect(jsonPath("$[?(@.type == 'mileage' && @.createdByName != null)]").exists());

        // чужая запись — 404
        mvc.perform(delete("/api/assets/" + bike + "/mileage/" + latestId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingOnlyMileageEntryResetsCacheToNull() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DEL1\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + accountId
                                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // сначала записываем пробег — кэш = значение записи
        recordMileage(admin, bike, "{\"mileageKm\":1200}").andExpect(status().isCreated());
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.mileageKm").value(1200));

        // удаляем единственную запись — без ошибки, кэш сбрасывается в null (журнал пуст)
        String logId = extract(mvc.perform(get("/api/assets/" + bike + "/mileage")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(delete("/api/assets/" + bike + "/mileage/" + logId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.mileageKm").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void futureMileageDateRejected() throws Exception {
        String admin = login();
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(createAsset(admin,
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-FUT1\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + accountId
                                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        // будущая дата при записи — 400
        String tomorrow = Instant.now().plus(1, ChronoUnit.DAYS).toString();
        recordMileage(admin, bike, "{\"mileageKm\":100,\"recordedAt\":\"" + tomorrow + "\"}")
                .andExpect(status().isBadRequest());

        // и при правке существующей записи — тоже 400
        recordMileage(admin, bike, "{\"mileageKm\":100}").andExpect(status().isCreated());
        String logId = extract(mvc.perform(get("/api/assets/" + bike + "/mileage")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString());
        mvc.perform(patch("/api/assets/" + bike + "/mileage/" + logId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"recordedAt\":\"" + tomorrow + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private org.springframework.test.web.servlet.ResultActions createAsset(String token, String body)
            throws Exception {
        return mvc.perform(post("/api/assets").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private org.springframework.test.web.servlet.ResultActions recordMileage(String token, String assetId, String body)
            throws Exception {
        return mvc.perform(post("/api/assets/" + assetId + "/mileage")
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

    private static String extract(String json) {
        return extract(json, "id");
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
