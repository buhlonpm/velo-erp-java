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

/** Дашборд: метрики по типам активов, просроченные и подходящие к концу аренды одним запросом. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DashboardTest {

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
    void dashboardAggregatesFleetAndRentals() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String purchase = ",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        String bike1 = extract(postJson(admin, "/api/assets",
                "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DB1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike2 = extract(postJson(admin, "/api/assets",
                "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DB2\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/assets",
                "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-DB1\"" + purchase + "}")
                .andExpect(status().isCreated());
        String customer = extract(postJson(admin, "/api/customers",
                "{\"fullName\":\"Даш Клиент\",\"phone\":\"+7 900 000-11-11\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // просроченная: срок 5 дней, выдана 10 дней назад → плановый конец 5 дней назад
        String overdueRental = extract(postJson(admin, "/api/rentals",
                "{\"customerId\":\"" + customer + "\",\"duration\":5,\"durationUnit\":\"day\","
                        + "\"items\":[{\"assetId\":\"" + bike1 + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + overdueRental + "/issue",
                "{\"date\":\"" + Instant.now().minus(10, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isOk());

        // подходящая к концу: срок 10 дней, выдана 9 дней назад → остался 1 день (< 20%)
        String endingSoonRental = extract(postJson(admin, "/api/rentals",
                "{\"customerId\":\"" + customer + "\",\"duration\":10,\"durationUnit\":\"day\","
                        + "\"items\":[{\"assetId\":\"" + bike2 + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + endingSoonRental + "/issue",
                "{\"date\":\"" + Instant.now().minus(9, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                // метрики по типам (порядок: bike, battery, charger): 2 велосипеда в аренде, 1 АКБ свободна
                .andExpect(jsonPath("$.assets[0].type").value("bike"))
                .andExpect(jsonPath("$.assets[0].total").value(2))
                .andExpect(jsonPath("$.assets[0].rented").value(2))
                .andExpect(jsonPath("$.assets[1].type").value("battery"))
                .andExpect(jsonPath("$.assets[1].available").value(1))
                .andExpect(jsonPath("$.assets[2].type").value("charger"))
                .andExpect(jsonPath("$.assets[2].total").value(0))
                // просроченная — в overdue, подходящая к концу — в endingSoon, и не наоборот
                .andExpect(jsonPath("$.overdue[?(@.id == '" + overdueRental + "')]").exists())
                .andExpect(jsonPath("$.overdue[?(@.id == '" + endingSoonRental + "')]").doesNotExist())
                .andExpect(jsonPath("$.endingSoon[?(@.id == '" + endingSoonRental + "')]").exists())
                .andExpect(jsonPath("$.endingSoon[?(@.id == '" + overdueRental + "')]").doesNotExist())
                .andExpect(jsonPath("$.overdue[0].customerName").value("Даш Клиент"))
                .andExpect(jsonPath("$.overdue[0].composition").value("Велосипед (VIN-DB1)"))
                .andExpect(jsonPath("$.overdue[0].status").value("overdue"))
                .andExpect(jsonPath("$.endingSoon[0].status").value("active"))
                // последние аренды: свежая сверху, с суммой и статусом
                .andExpect(jsonPath("$.latest.length()").value(2))
                .andExpect(jsonPath("$.latest[0].id").value(endingSoonRental))
                .andExpect(jsonPath("$.latest[0].amount").value(10000))
                .andExpect(jsonPath("$.latest[1].id").value(overdueRental));
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
