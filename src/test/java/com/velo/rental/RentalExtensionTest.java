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
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Аренда: черновик → оплата → выдача → продление → досрочный возврат с рефандом; лента событий. */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RentalExtensionTest {

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
    void draftPaymentIssueExtendAndEarlyReturnFlow() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-EXT1\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Продление Клиент\",\"phone\":\"+7 900 000-44-44\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // создание: срок 3 дня × 1000/день → черновик, конец периода считает сервер, оплаты нет
        Instant startAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        String expectedEnd = startAt.plus(3, ChronoUnit.DAYS).toString();
        String rentalBody = postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"startAt\":\"" + startAt + "\","
                                + "\"duration\":3,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("draft"))
                .andExpect(jsonPath("$.plannedEndAt").value(expectedEnd))
                .andExpect(jsonPath("$.amount").value(3000))
                .andExpect(jsonPath("$.paidAmount").value(0))
                .andExpect(jsonPath("$.extensions.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        String rentalId = extract(rentalBody, "id");

        // оплата из карточки: приходная операция по статье «Оплата аренды» + событие
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":3000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(3000));
        mvc.perform(get("/api/finance/transactions?kind=income").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.amount == 3000 && @.comment == 'Оплата аренды')]").exists());

        // выдача с явной датой = startAt → даты периода не сдвигаются, статус active
        postJson(admin, "/api/rentals/" + rentalId + "/issue", "{\"date\":\"" + startAt + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.plannedEndAt").value(expectedEnd));

        // продление на 7 дней от конца периода: 3 дн + 7 дн = 10 дней × 1000; денег при продлении нет
        // (продление — только в единице аренды: «неделя» для дневной аренды → 409)
        postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"week\"}")
                .andExpect(status().isConflict());
        String expectedEndAfterExtend = startAt.plus(10, ChronoUnit.DAYS).toString();
        String extendBody = postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":7,\"durationUnit\":\"day\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedEndAt").value(expectedEndAfterExtend))
                .andExpect(jsonPath("$.amount").value(10000))
                .andExpect(jsonPath("$.paidAmount").value(3000))
                .andExpect(jsonPath("$.extensions.length()").value(1))
                .andExpect(jsonPath("$.extensions[0].duration").value(7))
                .andExpect(jsonPath("$.extensions[0].durationUnit").value("day"))
                .andExpect(jsonPath("$.extensions[0].fromEndAt").value(expectedEnd))
                .andExpect(jsonPath("$.extensions[0].toEndAt").value(expectedEndAfterExtend))
                .andExpect(jsonPath("$.extensions[0].createdByName").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        assertThat(extract(extendBody, "createdAt", 1)).isNotBlank();

        // оплата продления — отдельным платежом из карточки
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":7000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(10000));

        // лента: создание, оплаты, выдача, продление со сдвигом срока (без суммы)
        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'created')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'payment' && @.amount == 3000)]").exists())
                .andExpect(jsonPath("$[?(@.type == 'payment' && @.amount == 7000)]").exists())
                .andExpect(jsonPath("$[?(@.type == 'issued')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'extension' && @.duration == 7"
                        + " && @.durationUnit == 'day' && @.fromEndAt == '" + expectedEnd
                        + "' && @.toEndAt == '" + expectedEndAfterExtend + "' && @.amount == null)]").exists())
                .andExpect(jsonPath("$[0].createdByName").isNotEmpty());

        // завершение с возвратом 500 ₽ (раньше конца периода → «завершена досрочно»)
        postJson(admin, "/api/rentals/" + rentalId + "/early-return",
                        "{\"refundAmount\":500,\"refundAccountId\":\"" + account + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed_early"))
                // paidAmount — только приходы (3000 + 7000), возврат — отдельным полем
                .andExpect(jsonPath("$.paidAmount").value(10000))
                .andExpect(jsonPath("$.refundedAmount").value(500));

        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'refund' && @.amount == 500)]").exists())
                .andExpect(jsonPath("$[?(@.type == 'completed')]").exists());

        mvc.perform(get("/api/finance/transactions?kind=expense").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.amount == 500)]").exists());

        // продлить завершённую нельзя
        postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"day\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void extendValidation() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String purchase = ",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"";
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Продление Клиент 2\",\"phone\":\"+7 900 000-55-55\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // продление «под выкуп» → 409 (срок задаёт график платежей)
        String charger = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"charger\",\"inventoryNumber\":\"CHG-EXT1\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String buyoutRental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\",\"buyoutPrice\":15000,"
                                + "\"termWeeks\":13,"
                                + "\"items\":[{\"assetId\":\"" + charger + "\"}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + buyoutRental + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"day\"}")
                .andExpect(status().isConflict());

        // черновик продлить нельзя — только после выдачи
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-EXT2\"" + purchase + "}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":800}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"day\"}")
                .andExpect(status().isConflict());

        // выдача без оплаты — ок (клиент доплатит позже)
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("active"));

        // продление без оплаты — ок, операция не создаётся, paidAmount не меняется
        postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"day\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paidAmount").value(0))
                .andExpect(jsonPath("$.extensions.length()").value(1))
                .andExpect(jsonPath("$.amount").value(2400)); // 3 дня × 800
    }

    /**
     * Просроченная аренда (создана задним числом): сумма фиксированная по периоду,
     * просрочка деньгами не досчитывается. Продление ВСЕГДА прибавляет срок к текущему концу
     * аренды (plannedEndAt + N×unit), без якоря «сейчас» — логика одинакова для активной
     * и просроченной аренды. Плюс правка/удаление продления с пересчётом срока и суммы.
     */
    @Test
    void extendOverdueRentalExtendsFromPlannedEnd() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-OVR1\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Просрочка Клиент\",\"phone\":\"+7 900 000-66-66\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        // аренда задним числом: старт 10 дней назад, срок 2 дня → 8 дней просрочки.
        // Буфер в час: elapsed должен стоять далеко от границы суток, иначе тест флакует —
        // между выдачей и продлением elapsed может перескочить на следующие сутки (ceil)
        Instant startAt = Instant.now().minus(10, ChronoUnit.DAYS).minus(1, ChronoUnit.HOURS)
                .truncatedTo(ChronoUnit.SECONDS);
        String rentalBody = postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"startAt\":\"" + startAt + "\","
                                + "\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.plannedEndAt").value(startAt.plus(2, ChronoUnit.DAYS).toString()))
                .andReturn().getResponse().getContentAsString();
        String rentalId = extract(rentalBody, "id");

        // выдача той же датой — период не сдвигается; аренда просрочена, но сумма
        // фиксированная по периоду (2 дня × 1000) — просрочка деньгами не досчитывается
        postJson(admin, "/api/rentals/" + rentalId + "/issue",
                        "{\"date\":\"" + startAt + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("overdue"))
                .andExpect(jsonPath("$.amount").value(2000));

        // продление просроченной: конец = исходный конец + 5 дн = startAt + 7 дн,
        // сумма 7 суток × 1000 — просрочка в срок и сумму не входит
        String extendBody = postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":5,\"durationUnit\":\"day\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plannedEndAt").value(startAt.plus(7, ChronoUnit.DAYS).toString()))
                .andExpect(jsonPath("$.amount").value(7000))
                .andExpect(jsonPath("$.extensions.length()").value(1))
                .andExpect(jsonPath("$.extensions[0].duration").value(5))
                .andExpect(jsonPath("$.extensions[0].durationUnit").value("day"))
                .andExpect(jsonPath("$.extensions[0].createdByName").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String extensionId = extractExtensionId(extendBody);

        // правка продления в чужой единице — 409 (только единица аренды)
        patchJson(admin, "/api/rentals/" + rentalId + "/extensions/" + extensionId,
                        "{\"duration\":1,\"durationUnit\":\"week\"}")
                .andExpect(status().isConflict());

        // правка продления 5 дн → 3 дн: конец = startAt + 5 дн, сумма пересчиталась (5 × 1000)
        String patchBody = patchJson(admin, "/api/rentals/" + rentalId + "/extensions/" + extensionId,
                        "{\"duration\":3,\"durationUnit\":\"day\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(5000))
                .andExpect(jsonPath("$.extensions.length()").value(1))
                .andExpect(jsonPath("$.extensions[0].duration").value(3))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(extract(patchBody, "plannedEndAt")))
                .isEqualTo(startAt.plus(5, ChronoUnit.DAYS));

        // удаление продления: конец возвращается к ИСХОДНОМУ концу периода (startAt + 2 дня),
        // сумма — обратно к фиксированной по периоду (2 × 1000), как будто продления не было
        String deleteBody = deleteJson(admin, "/api/rentals/" + rentalId + "/extensions/" + extensionId)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2000))
                .andExpect(jsonPath("$.extensions.length()").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(extract(deleteBody, "plannedEndAt")))
                .isEqualTo(startAt.plus(2, ChronoUnit.DAYS));

        // лента: создание, выдача, продление, правка, удаление
        mvc.perform(get("/api/rentals/" + rentalId + "/events").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'extension'"
                        + " && @.comment == 'Продление на 5 × день')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'extension'"
                        + " && @.comment == 'Продление изменено: было 5 × день → стало 3 × день')]").exists())
                .andExpect(jsonPath("$[?(@.type == 'extension' && @.fromEndAt == null"
                        + " && @.toEndAt == null && @.comment == 'Продление удалено (3 × день)')]").exists());
    }

    /** Правка/удаление продления доступны только у активной аренды. */
    @Test
    void extensionEditDeleteForbiddenAfterCompletion() throws Exception {
        String admin = login();
        String account = extract(getJson(admin, "/api/finance/accounts"), "id");
        String bike = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"VIN-OVR2\",\"purchasePrice\":50000,"
                                + "\"purchaseAccountId\":\"" + account + "\","
                                + "\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        String customer = extract(postJson(admin, "/api/customers",
                        "{\"fullName\":\"Завершённая Клиент\",\"phone\":\"+7 900 000-77-77\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rentalId = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":2,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + rentalId + "/issue", null)
                .andExpect(status().isOk());
        String extendBody = postJson(admin, "/api/rentals/" + rentalId + "/extend",
                        "{\"duration\":1,\"durationUnit\":\"day\"}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String extensionId = extractExtensionId(extendBody);

        // досрочный возврат → completed_early; продления больше не трогаем
        // (завершение — только при полной оплате: 2 дня + продление 1 день = 3000)
        postJson(admin, "/api/rentals/" + rentalId + "/payments",
                        "{\"amount\":3000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/rentals/" + rentalId + "/early-return", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed_early"));

        patchJson(admin, "/api/rentals/" + rentalId + "/extensions/" + extensionId,
                        "{\"duration\":2,\"durationUnit\":\"day\"}")
                .andExpect(status().isConflict());
        deleteJson(admin, "/api/rentals/" + rentalId + "/extensions/" + extensionId)
                .andExpect(status().isConflict());
    }

    private String login() throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@velo.local\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk()).andReturn();
        return result.getResponse().getContentAsString().split("\"accessToken\":\"")[1].split("\"")[0];
    }

    private ResultActions postJson(String token, String path, String body) throws Exception {
        var request = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON);
        return mvc.perform(body != null ? request.content(body) : request);
    }

    private ResultActions patchJson(String token, String path, String body) throws Exception {
        return mvc.perform(patch(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions deleteJson(String token, String path) throws Exception {
        return mvc.perform(delete(path).header("Authorization", "Bearer " + token));
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

    /** id первого продления в ответе аренды (поле extensions в конце, после позиций). */
    private static String extractExtensionId(String json) {
        Matcher matcher = Pattern.compile("\"extensions\":\\[\\{\"id\":\"([^\"]+)\"").matcher(json);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
