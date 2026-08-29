package com.velo.gps;

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

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «В комплекте с GPS-трекером»: цена 0, дата покупки от трекера, симка сразу в трекере. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class BundledSimCardTest {

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
    void bundledSimCardInheritsTrackerPurchaseAndInsertsIntoTracker() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");

        // трекер куплен за деньги — у комплектной симки цена 0 и дата покупки трекера
        String tracker = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"Teltonika FMB920\",\"purchasedAt\":\"2024-05-10T10:00:00Z\","
                                + "\"purchasePrice\":3500,\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 111-22-33\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.purchasePrice").value(0))
                .andExpect(jsonPath("$.purchasedAt").value("2024-05-10T10:00:00Z"))
                .andExpect(jsonPath("$.bundledTrackerId").value(tracker))
                .andExpect(jsonPath("$.bundledTrackerName").value("Teltonika FMB920"))
                .andExpect(jsonPath("$.trackerId").value(tracker))
                .andReturn().getResponse().getContentAsString(), "id");

        // симка сразу «вставлена» в трекер
        mvc.perform(get("/api/gps-trackers").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + tracker + "')].simCardId").value(sim));

        // расходная операция «Покупка SIM-карты» не создаётся (покупка трекера — есть)
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка SIM-карты: +7 900 111-22-33')]").isEmpty())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка GPS-трекера: Teltonika FMB920')]").exists());
    }

    @Test
    void bundledSimCardRejectsPrice() throws Exception {
        String admin = login();
        String tracker = createTracker(admin, "Coban GPS303");

        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 222-33-44\",\"operator\":\"Билайн\","
                                + "\"purchasePrice\":300,\"bundledTrackerId\":\"" + tracker + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void secondBundledSimCardOnSameTrackerConflicts() throws Exception {
        String admin = login();
        String tracker = createTracker(admin, "Concox GT06N");

        postJson(admin, "/api/sim-cards",
                "{\"phoneNumber\":\"+7 900 333-44-55\",\"operator\":\"МегаФон\","
                        + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}")
                .andExpect(status().isCreated());

        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 444-55-66\",\"operator\":\"Tele2\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void bundledTrackerMustExist() throws Exception {
        String admin = login();
        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 555-66-77\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void separatePurchaseRequiresDatePriceAndAccount() throws Exception {
        String admin = login();
        // без даты/цены/счёта — 409
        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 666-77-88\",\"operator\":\"МТС\"}"))
                .andExpect(status().isConflict());
        // цена 0 без bundledTrackerId — 409 (декларативный ноль больше не легален)
        mvc.perform(post("/api/sim-cards")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phoneNumber\":\"+7 900 666-77-88\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"2024-02-01T10:00:00Z\",\"purchasePrice\":0}"))
                .andExpect(status().isConflict());
        // трекер без данных покупки — 409
        mvc.perform(post("/api/gps-trackers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"Без покупки\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void availableFilterExcludesWrittenOffAndInsertedSimCards() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");

        String free = createSimCard(admin, account, "+7 900 777-88-99");
        String writtenOff = createSimCard(admin, account, "+7 900 888-99-00");
        mvc.perform(post("/api/sim-cards/" + writtenOff + "/write-off")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"broken\"}"))
                .andExpect(status().isOk());
        // комплектная симка сразу в трекере — тоже не «available»
        String tracker = createTracker(admin, "Xexun TK102");
        String bundled = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 999-00-11\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(get("/api/sim-cards?available=true").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + free + "')]").exists())
                .andExpect(jsonPath("$[?(@.id == '" + writtenOff + "')]").isEmpty())
                .andExpect(jsonPath("$[?(@.id == '" + bundled + "')]").isEmpty());
        // без фильтра списанная видна
        mvc.perform(get("/api/sim-cards").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + writtenOff + "')]").exists());
    }

    @Test
    void patchSimCardSyncsPurchaseTransaction() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        // цена 500, дата 2024-02-01 — из хелпера
        String sim = createSimCard(admin, account, "+7 900 101-01-01");

        mvc.perform(patch("/api/sim-cards/" + sim)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"2024-03-05T00:00:00Z\",\"purchasePrice\":700}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasePrice").value(700))
                .andExpect(jsonPath("$.purchasedAt").value("2024-03-05T00:00:00Z"));

        // системная операция покупки пересчитана (сумма и дата)
        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка SIM-карты: +7 900 101-01-01')].amount")
                        .value(700))
                .andExpect(jsonPath("$[?(@.comment == 'Покупка SIM-карты: +7 900 101-01-01')].date")
                        .value("2024-03-05T00:00:00Z"));
    }

    @Test
    void patchBundledSimCardPurchaseRejected() throws Exception {
        String admin = login();
        String tracker = createTracker(admin, "Suntech ST310");
        String sim = extract(postJson(admin, "/api/sim-cards",
                        "{\"phoneNumber\":\"+7 900 202-02-02\",\"operator\":\"МТС\","
                                + "\"purchasePrice\":0,\"bundledTrackerId\":\"" + tracker + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // комплектной симке цену/дату менять нельзя — наследуются от трекера
        mvc.perform(patch("/api/sim-cards/" + sim)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasePrice\":100}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/sim-cards/" + sim)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"2024-05-01T00:00:00Z\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void patchTrackerSyncsPurchaseTransaction() throws Exception {
        String admin = login();
        // цена 2500, дата 2024-01-15 — из хелпера
        String tracker = createTracker(admin, "Meitrack T399");

        mvc.perform(patch("/api/gps-trackers/" + tracker)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purchasedAt\":\"2024-04-01T00:00:00Z\",\"purchasePrice\":4200}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.purchasePrice").value(4200))
                .andExpect(jsonPath("$.purchasedAt").value("2024-04-01T00:00:00Z"));

        mvc.perform(get("/api/finance/transactions").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Покупка GPS-трекера: Meitrack T399')].amount")
                        .value(4200))
                .andExpect(jsonPath("$[?(@.comment == 'Покупка GPS-трекера: Meitrack T399')].date")
                        .value("2024-04-01T00:00:00Z"));
    }

    @Test
    void writeOffTrackerCascadesToSimCard() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String sim = createSimCard(admin, account, "+7 900 303-03-03");
        String tracker = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"Queclink GV300\",\"simCardId\":\"" + sim + "\","
                                + "\"purchasedAt\":\"2024-01-20T10:00:00Z\",\"purchasePrice\":3000,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        // списание трекера каскадно списывает симку: та же причина и комментарий, связь разорвана
        mvc.perform(post("/api/gps-trackers/" + tracker + "/write-off")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"stolen\",\"comment\":\"Украли из парка\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.simCardId").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/api/sim-cards").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].status").value("written_off"))
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].writeOffReason").value("stolen"))
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].writeOffComment").value("Украли из парка"));
    }

    @Test
    void writeOffTrackerSoldCascadesButCreatesSingleIncome() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String sim = createSimCard(admin, account, "+7 900 404-04-04");
        String tracker = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"WanWay S20\",\"simCardId\":\"" + sim + "\","
                                + "\"purchasedAt\":\"2024-01-25T10:00:00Z\",\"purchasePrice\":2800,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(post("/api/gps-trackers/" + tracker + "/write-off")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"sold\",\"salePrice\":1500,\"saleAccountId\":\"" + account + "\"}"))
                .andExpect(status().isOk());

        // симка уехала с причиной sold (исключение: отдельно sold для симки запрещён)
        mvc.perform(get("/api/sim-cards").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].status").value("written_off"))
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].writeOffReason").value("sold"));

        // приходная операция одна — трекерская; продажи симки нет
        mvc.perform(get("/api/finance/transactions?kind=income").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.comment == 'Продажа GPS-трекера: WanWay S20')]").exists())
                .andExpect(jsonPath("$[?(@.comment == 'Продажа SIM-карты: +7 900 404-04-04')]").isEmpty());
    }

    @Test
    void restoreTrackerLeavesSimCardWrittenOff() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String sim = createSimCard(admin, account, "+7 900 505-05-05");
        String tracker = extract(postJson(admin, "/api/gps-trackers",
                        "{\"model\":\"Concox JM01\",\"simCardId\":\"" + sim + "\","
                                + "\"purchasedAt\":\"2024-02-10T10:00:00Z\",\"purchasePrice\":2600,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");

        mvc.perform(post("/api/gps-trackers/" + tracker + "/write-off")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"broken\"}"))
                .andExpect(status().isOk());

        // restore трекера симку НЕ воскрешает
        mvc.perform(post("/api/gps-trackers/" + tracker + "/restore")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.simCardId").value(org.hamcrest.Matchers.nullValue()));

        mvc.perform(get("/api/sim-cards").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + sim + "')].status").value("written_off"));
    }

    @Test
    void writeOffTrackerWithoutSimCardWorksAsBefore() throws Exception {
        String admin = login();
        String tracker = createTracker(admin, "iStartek VT100");

        mvc.perform(post("/api/gps-trackers/" + tracker + "/write-off")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"lost\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("written_off"))
                .andExpect(jsonPath("$.writeOffReason").value("lost"))
                .andExpect(jsonPath("$.simCardId").value(org.hamcrest.Matchers.nullValue()));
    }

    private String createSimCard(String token, String account, String phone) throws Exception {
        return extract(postJson(token, "/api/sim-cards",
                        "{\"phoneNumber\":\"" + phone + "\",\"operator\":\"МТС\","
                                + "\"purchasedAt\":\"2024-02-01T10:00:00Z\",\"purchasePrice\":500,"
                                + "\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
    }

    private String createTracker(String token, String model) throws Exception {
        String account = extract(getJson(token, "/api/finance/accounts"), "id");
        return extract(postJson(token, "/api/gps-trackers",
                        "{\"model\":\"" + model + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\","
                                + "\"purchasePrice\":2500,\"purchaseAccountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "id");
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, String body)
            throws Exception {
        return mvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
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
