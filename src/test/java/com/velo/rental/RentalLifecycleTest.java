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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Жизненный цикл: черновик (активы в резерве) → оплата частями → выдача (можно без оплаты) →
 * завершение (complete — обычный путь) или досрочный возврат с рефандом (early-return — редкий путь).
 * Отменить можно только черновик; отмена освобождает активы из резерва и удаляет принятые платежи.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RentalLifecycleTest {

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
    void draftReservesAssetsAndCancelReleases() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Лайф Клиент\",\"phone\":\"+7 900 000-66-66\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-LC1\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // черновик: актив в резерве, повторное оформление → 409
        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andReturn().getResponse().getContentAsString(), "id");
        mvc.perform(get("/api/assets?status=reserved").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());
        postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isConflict());

        // оплата двумя платежами с указанием даты
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":600,\"accountId\":\"" + account + "\",\"date\":\"2026-08-01T10:00:00Z\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(600));
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":400,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(1000));

        // история оплат аренды: правка даты/суммы платежа и удаление (операции с rental_id)
        String payments = getJson(admin, "/api/finance/transactions?rentalId=" + rentalId);
        String latestTxId = extract(payments, "id", 0);   // платёж 400 (свежий)
        String earlierTxId = extract(payments, "id", 1);  // платёж 600 (2026-08-01)
        mvc.perform(patch("/api/finance/transactions/" + latestTxId)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":450,\"date\":\"2026-08-02T10:00:00Z\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(450))
                .andExpect(jsonPath("$.date").value("2026-08-02T10:00:00Z"));
        mvc.perform(get("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(1050));
        mvc.perform(delete("/api/finance/transactions/" + earlierTxId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/rentals/" + rentalId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(450));

        // отмена черновика: актив снова доступен
        postJson(admin, "/api/rentals/" + rentalId + "/cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"));
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());

        // платёж по отменённой → 409
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":100,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void issueWithoutPaymentAndEarlyReturn() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Лайф Клиент 2\",\"phone\":\"+7 900 000-77-77\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-LC2\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // выдача без оплаты — ок; актив «в аренде»; повторная выдача → 409
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));
        mvc.perform(get("/api/assets?status=rented").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isConflict());

        // отменить выданную нельзя
        postJson(admin, "/api/rentals/" + rentalId + "/cancel", null)
                .andExpect(status().isConflict());

        // завершение без полной оплаты → 409 (и обычное, и досрочное)
        postJson(admin, "/api/rentals/" + rentalId + "/complete", null)
                .andExpect(status().isConflict());
        postJson(admin, "/api/rentals/" + rentalId + "/early-return", null)
                .andExpect(status().isConflict());

        // доплата после выдачи — ок
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":2000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(2000));

        // завершение без рефанда (раньше конца периода → «завершена досрочно»), актив доступен
        postJson(admin, "/api/rentals/" + rentalId + "/early-return", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed_early"))
                .andExpect(jsonPath("$.paidAmount").value(2000));
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());

        // досрочный возврат у черновика/завершённой → 409
        postJson(admin, "/api/rentals/" + rentalId + "/early-return", null)
                .andExpect(status().isConflict());
    }

    @Test
    void cancelDraftDeletesPayments() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Отмена Клиент\",\"phone\":\"+7 900 000-88-88\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-LC3\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // платёж по черновику
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":1000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(1000));
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        // отмена черновика удаляет платёж и его событие, статус → cancelled
        postJson(admin, "/api/rentals/" + rentalId + "/cancel", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("cancelled"))
                .andExpect(jsonPath("$.paidAmount").value(0));
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'payment')]").doesNotExist())
                .andExpect(jsonPath("$[?(@.type == 'cancelled')]").exists());
    }

    @Test
    void completeReturnsAllItemsAndFinishes() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Комплит Клиент\",\"phone\":\"+7 900 000-99-99\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-LC4\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        // комплектная АКБ: цена 0, сразу смонтирована на велосипед
        String battery = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"battery\",\"inventoryNumber\":\"AKB-LC4\",\"purchasePrice\":0,"
                                + "\"bundledBikeId\":\"" + bike + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andReturn().getResponse().getContentAsString(), "id");

        // завершить черновик нельзя → 409
        postJson(admin, "/api/rentals/" + rentalId + "/complete", null)
                .andExpect(status().isConflict());

        String issuedJson = postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andReturn().getResponse().getContentAsString();
        String plannedEnd = extract(issuedJson, "plannedEndAt");

        // завершение без полной оплаты → 409 (сумма 1 день × 1000)
        postJson(admin, "/api/rentals/" + rentalId + "/complete", null)
                .andExpect(status().isConflict());
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":1000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(1000));

        // дата приёма дальше 24 часов от конца аренды → 409
        String tooLate = Instant.parse(plannedEnd).plusSeconds(25 * 3600).toString();
        postJson(admin, "/api/rentals/" + rentalId + "/complete", "{\"date\":\"" + tooLate + "\"}")
                .andExpect(status().isConflict());

        // обычное завершение с датой приёма = конец периода: статус completed, денежных операций нет
        postJson(admin, "/api/rentals/" + rentalId + "/complete", "{\"date\":\"" + plannedEnd + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.items[0].returnedAt").value(plannedEnd));
        // велосипед снова доступен, АКБ — на технике
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());
        mvc.perform(get("/api/assets?status=mounted").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + battery + "')]").exists());
        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'completed')]").exists());
        // завершение само по себе операций не создаёт — только ранее принятый платёж
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("income"));

        // повторное завершение → 409
        postJson(admin, "/api/rentals/" + rentalId + "/complete", null)
                .andExpect(status().isConflict());
    }

    @Test
    void earlyReturnWithRefund() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Рефанд Клиент\",\"phone\":\"+7 900 000-12-12\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-LC5\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":2000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk());

        // рефанд без счёта → 409
        postJson(admin, "/api/rentals/" + rentalId + "/early-return", "{\"refundAmount\":500}")
                .andExpect(status().isConflict());

        // досрочный возврат с рефандом: всегда completed_early, расходная операция создана
        postJson(admin, "/api/rentals/" + rentalId + "/early-return",
                        "{\"refundAmount\":500,\"refundAccountId\":\"" + account + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed_early"))
                // оплата не пересчитывается возвратом: paidAmount — только приходы, возврат — отдельно
                .andExpect(jsonPath("$.paidAmount").value(2000))
                .andExpect(jsonPath("$.refundedAmount").value(500));
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.kind == 'expense' && @.amount == 500)]").exists());
        // фильтр по kind вместе с rentalId: оплаты и возвраты не смешиваются
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId + "&kind=income")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("income"));
        mvc.perform(get("/api/finance/transactions?rentalId=" + rentalId + "&kind=expense")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("expense"))
                .andExpect(jsonPath("$[0].amount").value(500));
        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'refund' && @.amount == 500)]").exists())
                .andExpect(jsonPath("$[?(@.type == 'completed')]").exists());
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").exists());
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().split("\"accessToken\":\"")[1].split("\"")[0];
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
