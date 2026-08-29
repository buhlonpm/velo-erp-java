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
class WriteOffTest {

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
    void writeOffFlows() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");

        // велосипед + смонтированная АКБ
        String bike = createAsset(admin, "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-W1\","
                + "\"purchasePrice\":90000,\"purchaseAccountId\":\"" + account + "\"}");
        String battery = createAsset(admin, "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-W1\","
                + "\"purchasePrice\":40000,\"purchaseAccountId\":\"" + account + "\"}");
        postJson(admin, "/api/assets/" + battery + "/mount/" + bike, null).andExpect(status().isOk());

        // АКБ сломалась на велосипеде → списание, демонтаж
        postJson(admin, "/api/assets/" + battery + "/write-off", "{\"reason\":\"broken\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("decommissioned"))
                .andExpect(jsonPath("$.bikeId").isEmpty()); // демонтирована

        // в карточке велосипеда АКБ больше не смонтирована
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mountedBatteries").isEmpty())
                .andExpect(jsonPath("$.events[?(@.type == 'unmount')]").exists());

        // у самой АКБ в ленте — событие write_off
        mvc.perform(get("/api/assets/" + battery + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'write_off')]").exists());

        // повторное списание → 409
        postJson(admin, "/api/assets/" + battery + "/write-off", "{\"reason\":\"broken\"}")
                .andExpect(status().isConflict());

        // продажа велосипеда без цены → 409
        postJson(admin, "/api/assets/" + bike + "/write-off", "{\"reason\":\"sold\"}")
                .andExpect(status().isConflict());

        // продажа с ценой и счётом → sold + приходная операция
        postJson(admin, "/api/assets/" + bike + "/write-off",
                        "{\"reason\":\"sold\",\"salePrice\":120000,\"saleAccountId\":\"" + account + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sold"))
                .andExpect(jsonPath("$.writeOffReason").value("sold"));

        // приходная операция «Продажа оборудования» создана и привязана
        mvc.perform(get("/api/finance/transactions?kind=income").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.assetId == '" + bike + "' && @.amount == 120000)]").exists());
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return result.getResponse().getContentAsString().split("\"accessToken\":\"")[1].split("\"")[0];
    }

    private String createAsset(String token, String body) throws Exception {
        return extract(postJson(token, "/api/assets", body)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String path, String body)
            throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(body != null ? request.content(body) : request);
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
