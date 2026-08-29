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
                        "{\"phoneNumber\":\"+7 900 123-45-67\",\"operator\":\"МТС\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String trackerId = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"Teltonika FMB920\",\"simCardId\":\"" + simId + "\"}")
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

        // аренда с этим велосипедом (300/час — тариф задаётся в позиции)
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Деталь Клиент\",\"phone\":\"+7 900 000-00-07\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String plannedEnd = Instant.now().plus(5, ChronoUnit.HOURS).toString();
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"plannedEndAt\":\"" + plannedEnd + "\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":300}]}")
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

        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-T2\",\"purchasePrice\":0}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String tracker1 = extract(postJson(admin, "/api/gps-trackers", "{\"model\":\"FMB920 #1\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String tracker2 = extract(postJson(admin, "/api/gps-trackers", "{\"model\":\"FMB920 #2\"}")
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
