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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Проданные и списанные активы исключены из метрик парка на дашборде. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class DashboardExclusionTest {

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
    void soldAndDecommissionedAssetsExcludedFromStats() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String purchase = ",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        postJson(admin, "/api/assets", "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DX-KEEP\""
                + purchase + "}").andExpect(status().isCreated());
        String brokenBike = extract(postJson(admin, "/api/assets",
                "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-DX-BROKEN\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String soldCharger = extract(postJson(admin, "/api/assets",
                "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-DX-SOLD\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // один велосипед списан, зарядник продан — в метриках парка их быть не должно
        postJson(admin, "/api/assets/" + brokenBike + "/write-off", "{\"reason\":\"broken\"}")
                .andExpect(status().isOk());
        postJson(admin, "/api/assets/" + soldCharger + "/write-off",
                "{\"reason\":\"sold\",\"salePrice\":3000,\"saleAccountId\":\"" + account + "\"}")
                .andExpect(status().isOk());

        mvc.perform(get("/api/dashboard").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets[0].type").value("bike"))
                .andExpect(jsonPath("$.assets[0].total").value(1))
                .andExpect(jsonPath("$.assets[0].available").value(1))
                .andExpect(jsonPath("$.assets[2].type").value("charger"))
                .andExpect(jsonPath("$.assets[2].total").value(0));
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
