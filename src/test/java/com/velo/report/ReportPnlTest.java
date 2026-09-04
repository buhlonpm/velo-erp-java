package com.velo.report;

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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Отчёт P&L: агрегация по статьям за период, капекс отдельно, фильтры периода и счёта. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ReportPnlTest {

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
    void pnlGroupsByCategoryAndSeparatesCapex() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        String incomeCategory = createCategory(admin, "Отчёт Доход", "income");
        String expenseCategory = createCategory(admin, "Отчёт Расход", "expense");
        postJson(admin, "/api/finance/transactions",
                "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + incomeCategory
                        + "\",\"kind\":\"income\",\"amount\":1000}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/finance/transactions",
                "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + expenseCategory
                        + "\",\"kind\":\"expense\",\"amount\":300}")
                .andExpect(status().isCreated());
        // покупка велосипеда сейчас → системная расходная операция «Покупка оборудования» (капекс)
        postJson(admin, "/api/assets",
                "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-PNL1\",\"purchasePrice\":50000,"
                        + "\"purchaseAccountId\":\"" + account + "\",\"purchasedAt\":\""
                        + java.time.Instant.now() + "\"}")
                .andExpect(status().isCreated());
        // «Введение денег в бизнес» — системная статья: вложения владельца в P&L не входят
        List<String> ownerIds = com.jayway.jsonpath.JsonPath.read(
                getJson(admin, "/api/finance/categories"),
                "$[?(@.name == 'Введение денег в бизнес')].id");
        assertThat(ownerIds).hasSize(1);
        postJson(admin, "/api/finance/transactions",
                "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + ownerIds.get(0)
                        + "\",\"kind\":\"income\",\"amount\":7000}")
                .andExpect(status().isCreated());
        // статья системная — не удаляется
        mvc.perform(delete("/api/finance/categories/" + ownerIds.get(0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/reports/pnl?from=" + today + "&to=" + today)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.income[?(@.categoryName == 'Отчёт Доход' && @.total == 1000"
                        + " && @.capex == false)]").exists())
                // вложения владельца — не в списке приходов и не в итогах, отдельной цифрой
                .andExpect(jsonPath("$.income[?(@.categoryName == 'Введение денег в бизнес')]")
                        .doesNotExist())
                .andExpect(jsonPath("$.ownerInvestmentTotal").value(7000))
                .andExpect(jsonPath("$.expense[?(@.categoryName == 'Отчёт Расход' && @.total == 300"
                        + " && @.capex == false)]").exists())
                .andExpect(jsonPath("$.expense[?(@.categoryName == 'Покупка оборудования'"
                        + " && @.total == 50000 && @.capex == true)]").exists())
                .andExpect(jsonPath("$.incomeTotal").value(1000))
                .andExpect(jsonPath("$.expenseTotal").value(50300))
                .andExpect(jsonPath("$.capexOut").value(50000))
                .andExpect(jsonPath("$.capexIn").value(0))
                // операционная прибыль без капекса: 1000 − 300
                .andExpect(jsonPath("$.operatingProfit").value(700))
                // итог с капексом: 1000 − 50300
                .andExpect(jsonPath("$.netProfit").value(-49300));

        // период без операций → пусто
        LocalDate tomorrow = today.plusDays(1);
        mvc.perform(get("/api/reports/pnl?from=" + tomorrow + "&to=" + tomorrow)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeTotal").value(0))
                .andExpect(jsonPath("$.expenseTotal").value(0))
                .andExpect(jsonPath("$.netProfit").value(0));

        // фильтр по счёту: другой счёт — пустой отчёт
        String otherAccount = extract(postJson(admin, "/api/finance/accounts",
                        "{\"name\":\"Отчёт Пустой\",\"type\":\"cash\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(get("/api/reports/pnl?from=" + today + "&to=" + today
                        + "&accountId=" + otherAccount)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.incomeTotal").value(0))
                .andExpect(jsonPath("$.expenseTotal").value(0));

        // перевёрнутый период → 400
        mvc.perform(get("/api/reports/pnl?from=" + tomorrow + "&to=" + today)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());

        // менеджер без finance:view → 403
        postJson(admin, "/api/users",
                "{\"fullName\":\"Отчёт Менеджер\",\"email\":\"pnl-manager@velo.local\","
                        + "\"password\":\"manager123\",\"role\":\"MANAGER\"}")
                .andExpect(status().isCreated());
        String manager = login("pnl-manager@velo.local", "manager123");
        mvc.perform(get("/api/reports/pnl?from=" + today + "&to=" + today)
                        .header("Authorization", "Bearer " + manager))
                .andExpect(status().isForbidden());
    }

    @Test
    void bikePreparationIsCapex() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        // системная статья «Подготовка велосипеда» сидится при старте
        List<String> ids = com.jayway.jsonpath.JsonPath.read(
                getJson(admin, "/api/finance/categories"),
                "$[?(@.name == 'Подготовка велосипеда' && @.system == true)].id");
        assertThat(ids).hasSize(1);
        // удалить нельзя
        mvc.perform(delete("/api/finance/categories/" + ids.get(0))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict());

        postJson(admin, "/api/finance/transactions",
                "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + ids.get(0)
                        + "\",\"kind\":\"expense\",\"amount\":2000}")
                .andExpect(status().isCreated());

        // в P&L — капекс: в операционную прибыль не входит, в capexOut входит
        mvc.perform(get("/api/reports/pnl?from=" + today + "&to=" + today)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expense[?(@.categoryName == 'Подготовка велосипеда'"
                        + " && @.total == 2000 && @.capex == true)]").exists())
                .andExpect(jsonPath("$.expenseTotal").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2000)))
                .andExpect(jsonPath("$.capexOut").value(
                        org.hamcrest.Matchers.greaterThanOrEqualTo(2000)));
    }

    @Test
    void pnlWithoutFromStartsFromFirstTransaction() throws Exception {
        String admin = login("admin@velo.local", "admin123");
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        LocalDate today = LocalDate.now(ZoneId.systemDefault());

        // давняя операция задним числом — раньше любой другой в базе теста
        String incomeCategory = createCategory(admin, "Отчёт Давний Доход", "income");
        postJson(admin, "/api/finance/transactions",
                "{\"accountId\":\"" + account + "\",\"categoryId\":\"" + incomeCategory
                        + "\",\"kind\":\"income\",\"amount\":4200,\"date\":\"2020-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated());

        // без from — «за всё время»: старт от первой операции по кассе
        mvc.perform(get("/api/reports/pnl?to=" + today)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2020-01-15"))
                .andExpect(jsonPath("$.income[?(@.categoryName == 'Отчёт Давний Доход'"
                        + " && @.total == 4200)]").exists());
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
                .content(body));
    }

    private String createCategory(String token, String name, String kind) throws Exception {
        return extract(postJson(token, "/api/finance/categories",
                        "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
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
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
