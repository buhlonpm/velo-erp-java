package com.velo.rental;

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

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RentalFlowTest {

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
    void fullRentalCycle() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        // справочник моделей: создать + дубликат 409
        String modelId = extract(postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Wenbox\",\"model\":\"U7 Pro\",\"specs\":\"60V 45Ah\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Wenbox\",\"model\":\"U7 Pro\",\"specs\":\"x\"}")
                .andExpect(status().isConflict());

        // активы: два велосипеда, АКБ, зарядник (покупка обязательна)
        String bike1 = createBike(admin, modelId, "EV-101");
        String bike2 = createBike(admin, modelId, "EV-102");
        String battery = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-01\",\"voltage\":60,\"capacityAh\":45"
                                + purchaseFields(admin) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("АКБ 60V 45Ah"))
                .andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/assets",
                        "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-01\",\"powerW\":500"
                                + purchaseFields(admin) + "}")
                .andExpect(status().isCreated());

        // фильтр по типу
        mvc.perform(get("/api/assets?type=bike").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // клиент
        String customerId = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Тест Клиент\",\"phone\":\"+7 900 000-00-01\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // аренда без срока → 409
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"items\":[{\"assetId\":\"" + bike1 + "\"}]}")
                .andExpect(status().isConflict());

        // аренда на 1 час: 2 велосипеда с разными тарифами + доп. АКБ
        MvcResult rentalResult = postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"duration\":1,\"durationUnit\":\"hour\","
                                + "\"items\":[{\"assetId\":\"" + bike1 + "\",\"rate\":300,\"tariffUnit\":\"hour\"},"
                                + "{\"assetId\":\"" + bike2 + "\",\"rate\":250,\"tariffUnit\":\"day\"},"
                                + "{\"assetId\":\"" + battery + "\",\"tariffUnit\":\"hour\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].rate").value(300))
                .andExpect(jsonPath("$.items[0].tariffUnit").value("hour"))
                .andExpect(jsonPath("$.items[1].rate").value(250))
                .andExpect(jsonPath("$.items[2].rate").value(0))
                .andReturn();
        String rentalBody = rentalResult.getResponse().getContentAsString();
        String rentalId = extract(rentalBody, "id");

        // черновик: активы в резерве; повторное оформление → 409
        mvc.perform(get("/api/assets?status=reserved").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(3));
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"duration\":1,\"durationUnit\":\"hour\","
                                + "\"items\":[{\"assetId\":\"" + bike1 + "\"}]}")
                .andExpect(status().isConflict());

        // выдача: активы ушли в «в аренде»
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(get("/api/assets?status=rented").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(3));

        // возврат по позициям: одна — аренда ещё активна
        String firstItemId = extract(rentalBody, "id", 1);
        postJson(admin, "/api/rentals/" + rentalId + "/items/" + firstItemId + "/return", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        // возврат остальных → завершена, сумма посчитана
        String secondItemId = extract(rentalBody, "id", 2);
        String thirdItemId = extract(rentalBody, "id", 3);
        postJson(admin, "/api/rentals/" + rentalId + "/items/" + secondItemId + "/return", null)
                .andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rentalId + "/items/" + thirdItemId + "/return", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.amount").value(550)); // 1 ч × (300 + 250 + 0)

        // активы снова доступны
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(4));
    }

    @Test
    void rentToOwnRequiresBuyoutPriceAndTracksPayments() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        String customerId = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Выкуп Клиент\",\"phone\":\"+7 900 000-00-02\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        String charger = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-99\"" + purchaseFields(admin) + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // выкуп без цены → 409
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"kind\":\"rent_to_own\","
                                + "\"items\":[{\"assetId\":\"" + charger + "\"}]}")
                .andExpect(status().isConflict());

        // с ценой — ок, amount = цена выкупа
        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"kind\":\"rent_to_own\",\"buyoutPrice\":15000,"
                                + "\"items\":[{\"assetId\":\"" + charger + "\",\"tariffUnit\":\"month\"}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(15000))
                .andExpect(jsonPath("$.paidAmount").value(0))
                .andReturn().getResponse().getContentAsString(), "id");

        // платёж с привязкой к аренде → paidAmount растёт
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(), "id");
        String categoriesJson = mvc.perform(get("/api/finance/categories")
                        .header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString();
        Matcher matcher = Pattern.compile(
                "\\{\"id\":\"([^\"]+)\",\"name\":\"[^\"]*\",\"kind\":\"income\"").matcher(categoriesJson);
        assertThat(matcher.find()).isTrue();
        String incomeCategoryId = matcher.group(1);

        postJson(admin, "/api/finance/transactions",
                        "{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + incomeCategoryId
                                + "\",\"kind\":\"income\",\"amount\":5000,\"rentalId\":\"" + rentalId + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rentalId").value(rentalId));

        mvc.perform(get("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(5000));
    }

    @Test
    void tariffLinkedToModelAndSnapshotInRental() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        // модель
        String modelId = extract(postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Cube\",\"model\":\"Reaction\",\"specs\":\"\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // тариф модели: создание + дубликат 409 + редактирование цены
        String tariffId = extract(postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId + "\",\"name\":\"Дневной\",\"unit\":\"day\",\"price\":1500}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId + "\",\"name\":\"Дневной\",\"unit\":\"day\",\"price\":2000}")
                .andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/tariffs/" + tariffId).header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"price\":1600}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(1600));

        // модель отдаёт свои тарифы
        mvc.perform(get("/api/bike-models").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + modelId + "')].tariffs[0].price").value(1600));

        // аренда на 1 день по дневному тарифу: предоплатный период = 1 день × 1600
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"EV-201\",\"modelId\":\"" + modelId + "\""
                                + purchaseFields(admin) + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        String customerId = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Тариф Клиент\",\"phone\":\"+7 900 000-00-03\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
        String rentalBody = postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customerId + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1600}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(1600))
                .andReturn().getResponse().getContentAsString();
        String rentalId = extract(rentalBody, "id");

        // снапшот: удаление тарифа не трогает условия аренды
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/tariffs/" + tariffId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].rate").value(1600))
                .andExpect(jsonPath("$.items[0].tariffUnit").value("day"));
    }

    private String createBike(String token, String modelId, String inventoryNumber) throws Exception {
        return extract(postJson(token, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"" + inventoryNumber
                                + "\",\"modelId\":\"" + modelId + "\""
                                + purchaseFields(token) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelName").value("Wenbox U7 Pro 60V 45Ah"))
                .andReturn().getResponse().getContentAsString(), "id");
    }

    /** Обязательная покупка: цена + счёт списания (берём первый счёт из сидов). */
    private String purchaseFields(String token) throws Exception {
        String accountId = extract(mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString(), "id");
        return ",\"purchasePrice\":1000,\"purchaseAccountId\":\"" + accountId
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String path, String body)
            throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(body != null ? request.content(body) : request);
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
        return extract(json, field, 0);
    }

    /** occurrence 0 — первое вхождение; 1..N — id первой/второй/... позиции в items. */
    private static String extract(String json, String field, int occurrence) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        String found = null;
        for (int i = 0; i <= occurrence; i++) {
            assertThat(matcher.find()).as("поле %s (вхождение %d) в ответе", field, occurrence).isTrue();
            found = matcher.group(1);
        }
        return found;
    }
}
