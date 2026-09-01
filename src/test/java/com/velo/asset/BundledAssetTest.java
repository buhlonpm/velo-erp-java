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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «В комплекте с велосипедом»: цена 0, автомонтаж на выбранный велосипед, события в истории. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BundledAssetTest {

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
    void bundledBatteryAutoMountsAndWritesHistory() throws Exception {
        String admin = login();
        // у велосипеда своя дата покупки — комплектная АКБ должна унаследовать её
        String bike = createBike(admin, "VIN-BND1");

        // комплектная АКБ: цена 0, счёт не нужен, дата покупки — как у велосипеда
        String battery = extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-BND1\","
                                + "\"purchasePrice\":0,\"bundledBikeId\":\"" + bike + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bikeId").value(bike))
                .andExpect(jsonPath("$.bundledBikeId").value(bike))
                .andExpect(jsonPath("$.purchasedAt").value("2024-03-15T10:00:00Z"))
                .andReturn().getResponse().getContentAsString(), "id");

        // смонтирована на велосипеде, в итогах и истории
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mountedBatteries[?(@.id == '" + battery + "')]").exists())
                .andExpect(jsonPath("$.events[?(@.type == 'mount')]").exists());

        // у АКБ в ленте — покупка «в комплекте» и монтаж
        mvc.perform(get("/api/assets/" + battery + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'purchase')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'mount')]").exists());

        // покупка комплектного актива не редактируется: дата/цена — 409
        mvc.perform(patch("/api/assets/" + battery)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasePrice\":3000}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/assets/" + battery)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"2024-05-01T10:00:00Z\"}"))
                .andExpect(status().isConflict());
        // остальные поля править можно
        mvc.perform(patch("/api/assets/" + battery)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Комплектная АКБ\",\"voltage\":48}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Комплектная АКБ"))
                .andExpect(jsonPath("$.purchasePrice").value(0));
    }

    @Test
    void bundledRejectsPriceAndDuplicateMount() throws Exception {
        String admin = login();
        String bike = createBike(admin, "VIN-BND2");

        // комплектный актив с ценой > 0 — 409
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-BND2\","
                                + "\"purchasePrice\":3000,\"bundledBikeId\":\"" + bike + "\"}"))
                .andExpect(status().isConflict());

        // первая комплектная АКБ — ок, вторая на тот же велосипед — 409
        createBundled(admin, bike, "battery", "AKB-BND3");
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-BND4\","
                                + "\"purchasePrice\":0,\"bundledBikeId\":\"" + bike + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void bikePurchaseDateCascadesToBundledComponents() throws Exception {
        String admin = login();
        String bike = createBike(admin, "VIN-BND9");
        String battery = createBundled(admin, bike, "battery", "AKB-BND9");
        String charger = createBundled(admin, bike, "charger", "CHG-BND9");

        // смена даты покупки велосипеда — комплектные АКБ и зарядник наследуют новую дату
        mvc.perform(patch("/api/assets/" + bike)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"2024-06-20T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasedAt").value("2024-06-20T10:00:00Z"));
        mvc.perform(get("/api/assets/" + battery + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.purchasedAt").value("2024-06-20T10:00:00Z"));
        mvc.perform(get("/api/assets/" + charger + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.purchasedAt").value("2024-06-20T10:00:00Z"));
    }

    @Test
    void bundledChargerAutoMounts() throws Exception {
        String admin = login();
        String bike = createBike(admin, "VIN-BND5");

        String charger = createBundled(admin, bike, "charger", "CHG-BND1");
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mountedChargers[?(@.id == '" + charger + "')]").exists())
                .andExpect(jsonPath("$.events[?(@.type == 'mount')]").exists());

        // второй зарядник на тот же велосипед — 409
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"charger\",\"inventoryNumber\":\"CHG-BND2\","
                                + "\"purchasePrice\":0,\"bundledBikeId\":\"" + bike + "\"}"))
                .andExpect(status().isConflict());

        // зарядник демонтируется обратно на склад
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/assets/" + charger + "/mount")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bikeId").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void nonBundledRequiresDatePriceAndAccount() throws Exception {
        String admin = login();
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");

        // велосипед с ценой 0 — 409 (в комплекте купить нельзя)
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-BND6\","
                                + "\"purchasePrice\":0,\"purchasedAt\":\"2024-03-15T10:00:00Z\","
                                + "\"purchaseAccountId\":\"" + account + "\"}"))
                .andExpect(status().isConflict());

        // АКБ без режима «в комплекте» с ценой 0 — 409
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"battery\",\"inventoryNumber\":\"AKB-BND5\","
                                + "\"purchasePrice\":0,\"purchasedAt\":\"2024-03-15T10:00:00Z\"}"))
                .andExpect(status().isConflict());

        // без даты покупки — 409
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-BND7\","
                                + "\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account + "\"}"))
                .andExpect(status().isConflict());

        // без счёта списания — 409
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-BND8\","
                                + "\"purchasePrice\":50000,\"purchasedAt\":\"2024-03-15T10:00:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void futurePurchaseDateRejected() throws Exception {
        String admin = login();
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String future = Instant.now().plus(1, ChronoUnit.DAYS).toString();

        // создание актива с датой покупки в будущем — 400
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-FUT1\","
                                + "\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                                + "\",\"purchasedAt\":\"" + future + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Дата покупки не может быть в будущем"));

        // правка даты покупки на будущее — 400
        String bike = createBike(admin, "VIN-FUT2");
        mvc.perform(patch("/api/assets/" + bike)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"" + future + "\"}"))
                .andExpect(status().isBadRequest());

        // трекер с датой покупки в будущем — 400
        mvc.perform(post("/api/gps-trackers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"FUT-T1\",\"purchasePrice\":3000,"
                                + "\"purchaseAccountId\":\"" + account
                                + "\",\"purchasedAt\":\"" + future + "\"}"))
                .andExpect(status().isBadRequest());

        // симка с датой покупки в будущем — 400
        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 111-22-33\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":300,\"purchaseAccountId\":\"" + account
                                + "\",\"purchasedAt\":\"" + future + "\"}"))
                .andExpect(status().isBadRequest());
    }

    private String createBike(String token, String vin) throws Exception {
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "id");
        return extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"" + vin
                                + "\",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                                + "\",\"purchasedAt\":\"2024-03-15T10:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
    }

    private String createBundled(String token, String bikeId, String type, String inventoryNumber)
            throws Exception {
        return extract(mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"inventoryNumber\":\"" + inventoryNumber
                                + "\",\"purchasePrice\":0,\"bundledBikeId\":\"" + bikeId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
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
