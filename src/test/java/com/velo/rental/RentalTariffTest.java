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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Единица тарифа позиции = единице срока аренды (rent): сервер ставит её сам,
 * присланная в позиции игнорируется. Справочник тарифов модели арендой не пополняется —
 * он только автоподставляет цену на фронте. У rent_to_own единица позиции — всегда week (ставит сервер).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RentalTariffTest {

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
    void itemUnitFollowsRentalDurationAndCatalogUntouched() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String purchase = ",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String modelId = extract(postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Wenbox\",\"model\":\"T9\",\"specs\":\"48V 20Ah\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Тариф Клиент\",\"phone\":\"+7 900 000-33-33\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // позиция без единицы — ок: сервер ставит единицу срока аренды (day)
        String bike1 = createBike(admin, modelId, "VIN-TAR1", purchase);
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike1 + "\",\"rate\":1500}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].tariffUnit").value("day"))
                .andExpect(jsonPath("$.items[0].rate").value(1500))
                .andExpect(jsonPath("$.amount").value(3000));

        // присланная в позиции единица игнорируется — снова единица срока аренды
        String bike2 = createBike(admin, modelId, "VIN-TAR2", purchase);
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":3,\"durationUnit\":\"week\","
                                + "\"items\":[{\"assetId\":\"" + bike2 + "\",\"tariffUnit\":\"hour\",\"rate\":7000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].tariffUnit").value("week"))
                .andExpect(jsonPath("$.amount").value(21000));

        // справочник модели арендами не пополняется
        mvc.perform(get("/api/tariffs?modelId=" + modelId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // rent_to_own: единицу позиции не шлём — сервер ставит week, график на termWeeks платежей
        String bike3 = createBike(admin, modelId, "VIN-TAR3", purchase);
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\",\"buyoutPrice\":90000,"
                                + "\"termWeeks\":26,"
                                + "\"items\":[{\"assetId\":\"" + bike3 + "\",\"rate\":1500}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[0].tariffUnit").value("week"))
                .andExpect(jsonPath("$.schedule.length()").value(26));
    }

    /**
     * Тариф под выкуп: один на модель, строго недельный, живёт рядом с недельным
     * тарифом аренды (уникальность по model+name+unit+kind).
     */
    @Test
    void buyoutTariffRules() throws Exception {
        String admin = login();
        String modelId = extract(postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Wenbox\",\"model\":\"BO1\",\"specs\":\"48V 20Ah\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // недельный тариф аренды
        postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId + "\",\"name\":\"Недельный\",\"unit\":\"week\","
                                + "\"price\":3000}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("rent"));

        // тариф под выкуп — недельный, ок
        postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId + "\",\"name\":\"Под выкуп\",\"unit\":\"week\","
                                + "\"price\":3500,\"kind\":\"rent_to_own\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.kind").value("rent_to_own"));

        // второй тариф под выкуп — 409
        postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId + "\",\"name\":\"Под выкуп 2\",\"unit\":\"week\","
                                + "\"price\":4000,\"kind\":\"rent_to_own\"}")
                .andExpect(status().isConflict());

        // тариф под выкуп не в неделях — 409
        String modelId2 = extract(postJson(admin, "/api/bike-models",
                        "{\"brand\":\"Wenbox\",\"model\":\"BO2\",\"specs\":\"48V 20Ah\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/tariffs",
                        "{\"modelId\":\"" + modelId2 + "\",\"name\":\"Под выкуп\",\"unit\":\"day\","
                                + "\"price\":500,\"kind\":\"rent_to_own\"}")
                .andExpect(status().isConflict());
    }

    private String createBike(String token, String modelId, String inventoryNumber, String purchase)
            throws Exception {
        return extract(postJson(token, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"" + inventoryNumber
                                + "\",\"modelId\":\"" + modelId + "\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().split("\"accessToken\":\"")[1].split("\"")[0];
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String path, String body)
            throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String getJson(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
