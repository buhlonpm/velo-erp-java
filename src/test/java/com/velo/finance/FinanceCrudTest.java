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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
class FinanceCrudTest {

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
    void accountCrudAndDeleteProtection() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        // создание: баланс нулевой (начального остатка больше нет)
        MvcResult created = mvc.perform(post("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Карта Тинькофф\",\"type\":\"card\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.balance").value(0))
                .andReturn();
        String accountId = extract(created.getResponse().getContentAsString(), "id");

        // редактирование названия
        mvc.perform(patch("/api/finance/accounts/" + accountId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Карта Т-банк\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Карта Т-банк"));

        // удалить пустой счёт можно
        MvcResult empty = mvc.perform(post("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Пустой счёт\",\"type\":\"cash\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String emptyId = extract(empty.getResponse().getContentAsString(), "id");
        mvc.perform(delete("/api/finance/accounts/" + emptyId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // а счёт с операциями — нельзя (409)
        String categoryId = createCategory(admin, "Продажа запчастей", "income");
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + categoryId
                                + "\",\"kind\":\"income\",\"amount\":700}"))
                .andExpect(status().isCreated());

        mvc.perform(delete("/api/finance/accounts/" + accountId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        // и его баланс = сумма прихода
        mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].balance").value(700));
    }

    @Test
    void transactionEditAndDeleteRecalculatesBalance() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String accountId = firstAccountId(admin);
        String categoryId = createCategory(admin, "Прочие доходы", "income");

        String transactionId = extract(mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + categoryId
                                + "\",\"kind\":\"income\",\"amount\":1000}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // правка суммы → баланс пересчитан
        mvc.perform(patch("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":800}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(800));
        assertBalance(admin, accountId, 800);

        // статья другого типа → 409
        String expenseCategoryId = createCategory(admin, "Прочие расходы", "expense");
        mvc.perform(patch("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":\"" + expenseCategoryId + "\"}"))
                .andExpect(status().isConflict());

        // удаление давнишней операции → баланс снова корректен (ничего пересчитывать не нужно)
        mvc.perform(delete("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        assertBalance(admin, accountId, 0);

        // менеджер без права finance:view не может править историю
        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nofinance@velo.local\",\"fullName\":\"Без Финансов\","
                                + "\"password\":\"nofinance-pass\",\"role\":\"MANAGER\"}"))
                .andExpect(status().isCreated());
        String manager = login("nofinance@velo.local", "nofinance-pass");
        mvc.perform(delete("/api/finance/transactions/" + transactionId)
                        .header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());
    }

    private void assertBalance(String token, String accountId, int expected) throws Exception {
        mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + accountId + "')].balance").value(expected));
    }

    @Test
    void categoryCrudAndDeleteProtection() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        String categoryId = createCategory(admin, "Сувениры", "income");

        // дубликат (имя + тип) — 409
        mvc.perform(post("/api/finance/categories")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Сувениры\",\"kind\":\"income\"}"))
                .andExpect(status().isConflict());

        // свободную статью удалить можно
        mvc.perform(delete("/api/finance/categories/" + categoryId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());

        // занятую операцией — нельзя
        String accountId = firstAccountId(admin);
        String usedCategoryId = createCategory(admin, "Прокат снаряжения", "income");
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + usedCategoryId
                                + "\",\"kind\":\"income\",\"amount\":300}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/finance/categories/" + usedCategoryId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());
    }

    @Test
    void transactionValidationAndFilters() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String accountId = firstAccountId(admin);
        String incomeCategoryId = createCategory(admin, "Депозит возврат", "income");
        String expenseCategoryId = createCategory(admin, "Интернет", "expense");

        // сумма <= 0 → 400
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + incomeCategoryId
                                + "\",\"kind\":\"income\",\"amount\":0}"))
                .andExpect(status().isBadRequest());

        // тип операции не совпадает со статьёй → 409
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + expenseCategoryId
                                + "\",\"kind\":\"income\",\"amount\":100}"))
                .andExpect(status().isConflict());

        // несуществующий счёт → 404
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + UUID.randomUUID() + "\",\"categoryId\":\"" + incomeCategoryId
                                + "\",\"kind\":\"income\",\"amount\":100}"))
                .andExpect(status().isNotFound());

        // несуществующая статья → 404
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + UUID.randomUUID()
                                + "\",\"kind\":\"income\",\"amount\":100}"))
                .andExpect(status().isNotFound());

        // фильтры: по счёту и по типу
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + expenseCategoryId
                                + "\",\"kind\":\"expense\",\"amount\":900}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/finance/transactions?accountId=" + accountId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.accountId != '" + accountId + "')]").isEmpty());

        mvc.perform(get("/api/finance/transactions?kind=expense")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind != 'expense')]").isEmpty());
    }

    @Test
    void managerWithFinanceViewSeesEverythingAndCanExpense() throws Exception {
        String admin = login("admin@velo.local", "admin123");

        mvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"finmanager@velo.local\",\"fullName\":\"Финменеджер\","
                                + "\"password\":\"fin-pass-123\",\"role\":\"MANAGER\","
                                + "\"permissions\":[\"finance:view\"]}"))
                .andExpect(status().isCreated());
        String manager = login("finmanager@velo.local", "fin-pass-123");

        mvc.perform(get("/api/finance/accounts").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk());
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + manager))
                .andExpect(status().isOk());

        String accountId = firstAccountId(admin);
        String expenseCategoryId = createCategory(admin, "Кофе в офис", "expense");
        mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + manager)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + expenseCategoryId
                                + "\",\"kind\":\"expense\",\"amount\":600}"))
                .andExpect(status().isCreated());
    }

    @Test
    void systemTransactionsAreProtectedFromEditAndDelete() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        // свой счёт: первый счёт используют другие тесты, не засоряем его баланс
        String accountId = extract(mvc.perform(post("/api/finance/accounts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Счёт системных операций\",\"type\":\"cash\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // покупка велосипеда создаёт системную операцию
        mvc.perform(post("/api/assets")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"bike\",\"inventoryNumber\":\"VIN-SYS1\",\"purchasePrice\":5000,"
                                + "\"purchaseAccountId\":\"" + accountId + "\"}"))
                .andExpect(status().isCreated());

        // покупка GPS-трекера — тоже системная (прямой связи с транзакцией нет)
        mvc.perform(post("/api/gps-trackers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Teltonika FMB920\",\"purchasePrice\":3000,"
                                + "\"purchaseAccountId\":\"" + accountId + "\"}"))
                .andExpect(status().isCreated());

        MvcResult list = mvc.perform(get("/api/finance/transactions?kind=expense")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andReturn();
        Matcher systemIds = Pattern.compile("\\{\"id\":\"([^\"]+)\",[^{}]*?\"system\":true")
                .matcher(list.getResponse().getContentAsString());
        List<String> ids = new ArrayList<>();
        while (systemIds.find()) {
            ids.add(systemIds.group(1));
        }
        assertThat(ids).as("системные операции покупок").hasSize(2);

        // ни править, ни удалить системную операцию нельзя
        for (String id : ids) {
            mvc.perform(patch("/api/finance/transactions/" + id)
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount\":1}"))
                    .andExpect(status().isConflict());
            mvc.perform(delete("/api/finance/transactions/" + id)
                            .header("Authorization", "Bearer " + admin))
                    .andExpect(status().isConflict());
        }

        // баланс не пострадал: обе покупки на месте
        assertBalance(admin, accountId, -8000);

        // ручная операция по-прежнему удаляется
        String manualCategoryId = createCategory(admin, "Штрафы", "expense");
        String manualId = extract(mvc.perform(post("/api/finance/transactions")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":\"" + accountId + "\",\"categoryId\":\"" + manualCategoryId
                                + "\",\"kind\":\"expense\",\"amount\":100}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.system").value(false))
                .andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(delete("/api/finance/transactions/" + manualId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return extract(result.getResponse().getContentAsString(), "accessToken");
    }

    private String createCategory(String token, String name, String kind) throws Exception {
        MvcResult result = mvc.perform(post("/api/finance/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return extract(result.getResponse().getContentAsString(), "id");
    }

    private String firstAccountId(String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/finance/accounts")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return extract(result.getResponse().getContentAsString(), "id");
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).as("поле %s в ответе", field).isTrue();
        return matcher.group(1);
    }
}
