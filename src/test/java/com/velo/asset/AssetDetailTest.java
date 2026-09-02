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
class AssetDetailTest {

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
    void detailAggregatesPurchaseMileageFinanceAndRentals() throws Exception {
        String admin = login();
        String purchased = Instant.now().minus(90, ChronoUnit.DAYS).toString();
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");

        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-D1\",\"purchasedAt\":\"" + purchased
                                + "\",\"purchasePrice\":180000,\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // GPS: симка + трекер из справочников, установка на велосипед
        String simId = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 123-45-67\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"" + purchased + "\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String trackerId = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"Teltonika FMB920\",\"simCardId\":\"" + simId + "\","
                                + "\"purchasedAt\":\"" + purchased + "\",\"purchasePrice\":3000,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(post("/api/assets/" + bike + "/tracker/" + trackerId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gpsTrackerModel").value("Teltonika FMB920"))
                .andExpect(jsonPath("$.gpsOperator").value("МТС"));

        // пробег
        postJson(admin, "/api/assets/" + bike + "/mileage", "{\"mileageKm\":2200}")
                .andExpect(status().isCreated());

        // расход с привязкой к активу
        String expenseCategory = extract(postJson(admin, "/api/finance/categories",
                        "{\"name\":\"Ремонт активов\",\"kind\":\"expense\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/finance/transactions",
                        "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + expenseCategory
                                + "\",\"kind\":\"expense\",\"amount\":4500,\"assetId\":\"" + bike + "\"}")
                .andExpect(status().isCreated());

        // аренда на 1 час с этим велосипедом (300/час — тариф задаётся в позиции)
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Деталь Клиент\",\"phone\":\"+7 900 000-00-07\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"hour\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":300,\"tariffUnit\":\"hour\"}]}")
                .andExpect(status().isCreated());

        // карточка
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.inventoryNumber").value("VIN-D1"))
                .andExpect(jsonPath("$.asset.gpsTrackerModel").value("Teltonika FMB920"))
                .andExpect(jsonPath("$.asset.gpsOperator").value("МТС"))
                .andExpect(jsonPath("$.mileageLog[0].mileageKm").value(2200))
                .andExpect(jsonPath("$.transactions.length()").value(2)) // покупка + ремонт
                .andExpect(jsonPath("$.rentals.length()").value(1))
                .andExpect(jsonPath("$.totals.purchasePrice").value(180000))
                .andExpect(jsonPath("$.totals.expensesTotal").value(4500))
                .andExpect(jsonPath("$.totals.rentalAccruedTotal").value(300));
    }

    @Test
    void secondTrackerInstallRejected() throws Exception {
        String admin = login();
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String purchase = ",\"purchasedAt\":\"2024-01-10T10:00:00Z\",\"purchasePrice\":2000,"
                + "\"purchaseAccountId\":\"" + account + "\"";

        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-T2\",\"purchasePrice\":50000,"
                                + "\"purchasedAt\":\"2024-01-10T10:00:00Z\",\"purchaseAccountId\":\""
                                + account + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String tracker1 = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"FMB920 #1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String tracker2 = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"FMB920 #2\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(post("/api/assets/" + bike + "/tracker/" + tracker1)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // второй трекер на тот же велосипед — 409, замены нет
        mvc.perform(post("/api/assets/" + bike + "/tracker/" + tracker2)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        // сняли — второй устанавливается
        mvc.perform(delete("/api/assets/" + bike + "/tracker")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(post("/api/assets/" + bike + "/tracker/" + tracker2)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
    }

    @Test
    void assetTransactionEventsAppearInAssetHistory() throws Exception {
        String admin = login();
        String account = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");

        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-E1\",\"purchasePrice\":5000,"
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\",\"purchaseAccountId\":\""
                                + account + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String category = extract(postJson(admin, "/api/finance/categories",
                        "{\"name\":\"Ремонт вилки\",\"kind\":\"expense\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // создание операции по активу → событие в ленте актива
        String transactionId = extract(postJson(admin, "/api/finance/transactions",
                        "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + category
                                + "\",\"kind\":\"expense\",\"amount\":1500,\"assetId\":\"" + bike + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String events = mvc.perform(get("/api/assets/" + bike + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(events).contains("\"type\":\"expense\"")
                .contains("Расход: 1 500 ₽ · Ремонт вилки")
                .contains(transactionId);

        // правка суммы → событие об изменении
        mvc.perform(patch("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":2000}"))
                .andExpect(status().isOk());
        String afterEdit = mvc.perform(get("/api/assets/" + bike + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterEdit).contains("Расход изменён: сумма: 1 500 ₽ → 2 000 ₽");

        // удаление → событие об удалении, ссылка на операцию снята (FK не мешает удалению)
        mvc.perform(delete("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        String afterDelete = mvc.perform(get("/api/assets/" + bike + "/events")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(afterDelete).contains("Расход удалён: 2 000 ₽ · Ремонт вилки")
                .doesNotContain(transactionId);
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String path, String body)
            throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().split("\"accessToken\":\"")[1].split("\"")[0];
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
