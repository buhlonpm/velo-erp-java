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
class RentalKitTest {

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
    void mountedBatteryJoinsAsKitAndReturnsWithParent() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String purchase = ",\"purchasePrice\":1000,\"purchaseAccountId\":\"" + account
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";

        // велосипед + смонтированные АКБ и зарядник
        String bike = createAsset(admin, "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-K1\"" + purchase + "}");
        String battery = createAsset(admin, "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-K1\"" + purchase + "}");
        String charger = createAsset(admin, "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-K1\"" + purchase + "}");
        mvc.perform(post("/api/assets/" + battery + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());
        mvc.perform(post("/api/assets/" + charger + "/mount/" + bike)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Кит Клиент\",\"phone\":\"+7 900 000-11-11\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // аренда только с велосипедом — АКБ и зарядник подтягиваются автоматом дочерними позициями
        String rentalBody = postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":3,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":300}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn().getResponse().getContentAsString();
        String rentalId = extract(rentalBody, "id");

        // дочерние позиции: АКБ и зарядник, тариф 0, единица тарифа — как у велосипеда (день, не час)
        mvc.perform(get("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.parentItemId == null && @.assetId == '" + bike + "')]").exists())
                .andExpect(jsonPath("$.items[?(@.parentItemId != null && @.assetId == '" + battery
                        + "' && @.rate == 0 && @.tariffUnit == 'day')]").exists())
                .andExpect(jsonPath("$.items[?(@.parentItemId != null && @.assetId == '" + charger
                        + "' && @.rate == 0 && @.tariffUnit == 'day')]").exists());

        // все три актива в резерве (черновик), после выдачи — в аренде
        mvc.perform(get("/api/assets?status=reserved").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(3));
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(get("/api/assets?status=rented").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(3));

        // возврат родителя → дочерние возвращаются автоматом, аренда завершена
        String parentItemId = extract(rentalBody, "id", 1);
        postJson(admin, "/api/rentals/" + rentalId + "/items/" + parentItemId + "/return", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.items[?(@.assetId == '" + battery + "')].returnedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[?(@.assetId == '" + charger + "')].returnedAt").isNotEmpty());
        // после возврата велосипед снова доступен, а АКБ и зарядник остаются на технике
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/assets?status=mounted").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.length()").value(2));
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
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
        return extract(json, field, 0);
    }

    private static String extract(String json, String field, int occurrence) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        String found = null;
        for (int i = 0; i <= occurrence; i++) {
            assertThat(matcher.find()).isTrue();
            found = matcher.group(1);
        }
        return found;
    }
}
