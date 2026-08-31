package com.velo.customer;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Удаление клиента: только если по нему нет аренд (иначе 409). */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CustomerDeletionTest {

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
    void customerWithoutRentalsCanBeDeleted() throws Exception {
        String admin = login();
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Без Аренд\",\"phone\":\"+7 900 800-00-01\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(delete("/api/customers/" + customer).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/customers/" + customer).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void customerWithRentalCannotBeDeletedUntilRentalIsGone() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"С Арендой\",\"phone\":\"+7 900 800-00-02\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"DEL-BIKE-C1\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // есть аренда — удалить нельзя; после бесследного удаления черновика клиента удалить можно
        mvc.perform(delete("/api/customers/" + customer).header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
        mvc.perform(delete("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/customers/" + customer).header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
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
