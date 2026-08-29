package com.velo.finance;

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
class FinanceFlowTest {

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
    void financePermissionRules() throws Exception {
        String adminToken = login("admin@velo.local", "admin123");

        // сид создал счёт и статьи
        String accounts = mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String accountId = extract(accounts, "id");

        String categories = mvc.perform(get("/api/finance/categories")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String incomeCategoryId = extractByKind(categories, "income");

        // менеджер без права finance:view
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"cashier@velo.local\",\"fullName\":\"Кассир\","
                                + "\"password\":\"cashier-pass-1\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated());
        String cashierToken = login("cashier@velo.local", "cashier-pass-1");

        // не видит счета и операции
        mvc.perform(get("/api/finance/accounts").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + cashierToken))
                .andExpect(status().isForbidden());

        // но может принять приход
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + incomeCategoryId
                                + "\",\"kind\":\"income\",\"amount\":1500,\"comment\":\"Оплата аренды\"}"))
                .andExpect(status().isCreated());

        // а расход — нет
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + cashierToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + incomeCategoryId
                                + "\",\"kind\":\"expense\",\"amount\":500}"))
                .andExpect(status().isForbidden());

        // баланс счёта вырос на сумму прихода
        mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].balance").value(1500));
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
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("поле %s в ответе", field).isTrue();
        return matcher.group(1);
    }

    private static String extractByKind(String json, String kind) {
        Matcher matcher = Pattern.compile(
                "\\{\"id\":\"([^\"]+)\",\"name\":\"[^\"]*\",\"kind\":\"" + kind + "\"").matcher(json);
        assertThat(matcher.find()).as("категория типа %s", kind).isTrue();
        return matcher.group(1);
    }
}
