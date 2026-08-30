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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Договор «под выкуп»: срок 13/26/52 недели, график еженедельных платежей (сумма строк = цене
 * выкупа), погашение — FIFO из платежей. Переплата — по выбору: сократить срок (shorten_term)
 * или уменьшить следующие платежи (reduce_next); без стратегии переплата гасит ближайшие.
 * Полная оплата → «выкуп завершён»: техника уходит клиенту (bought_out, комплект тоже).
 * Расторжение (early-return) — без возврата денег.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RentalBuyoutTest {

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
    void scheduleGeneratedOnCreateAndShiftsOnIssue() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп График");
        String bike = createBike(admin, account, "VIN-BO1");

        Instant startAt = Instant.now().plus(2, ChronoUnit.DAYS);
        String rental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\","
                                + "\"buyoutPrice\":39000,\"termWeeks\":13,"
                                + "\"startAt\":\"" + startAt + "\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":3000}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.termWeeks").value(13))
                .andExpect(jsonPath("$.amount").value(39000))
                .andExpect(jsonPath("$.schedule.length()").value(13))
                .andExpect(jsonPath("$.schedule[0].seq").value(1))
                .andExpect(jsonPath("$.schedule[0].amount").value(3000))
                .andExpect(jsonPath("$.schedule[0].status").value("next"))
                .andExpect(jsonPath("$.schedule[1].status").value("pending"))
                .andReturn().getResponse().getContentAsString(), "id");

        // последний платёж через 12 недель от начала; plannedEndAt = его дата
        String createdBody = getJson(admin, "/api/rentals/" + rental);
        assertThat(Instant.parse(extract(createdBody, "plannedEndAt")))
                .isEqualTo(startAt.plus(84, ChronoUnit.DAYS));

        // выдача сейчас сдвигает график вместе с датой начала аренды
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule[0].status").value("next"));

        String body = getJson(admin, "/api/rentals/" + rental);
        Instant firstDue = Instant.parse(extractDates(body, "dueDate"));
        assertThat(firstDue).isBefore(startAt); // сдвинулся с планового startAt на момент выдачи
    }

    @Test
    void overpaymentShortensTerm() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Сокращение");
        String bike = createBike(admin, account, "VIN-BO2");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        Instant endBefore = Instant.parse(extract(getJson(admin, "/api/rentals/" + rental), "plannedEndAt"));

        // клиент платит сразу 3 недели и просит сократить срок: платёж тот же (3000),
        // оплаченные авансом будущие недели поглощаются — график идёт со следующей недели,
        // конец на 2 недели ближе; оплатить осталось ровно 39000 − 9000 = 30000
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account + "\","
                                + "\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule[0].status").value("paid"))
                .andExpect(jsonPath("$.schedule[1].status").value("next"))
                .andExpect(jsonPath("$.schedule[1].amount").value(3000))
                .andExpect(jsonPath("$.schedule.length()").value(11));

        String body = getJson(admin, "/api/rentals/" + rental);
        Instant endAfter = Instant.parse(extract(body, "plannedEndAt"));
        // срок сократился на 2 оплаченных сверх плана недели (с точностью до секунд выдачи)
        assertThat(endBefore.getEpochSecond() - endAfter.getEpochSecond())
                .isBetween(14L * 86_400 - 5, 14L * 86_400 + 5);
        // остаток по графику = остаток по деньгам: итог договора не изменился
        assertThat(sumScheduleRemaining(body)).isEqualTo(39000 - 9000);
    }

    @Test
    void overpaymentReducesNextPayments() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Уменьшение");
        String bike = createBike(admin, account, "VIN-BO3");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        Instant endBefore = Instant.parse(extract(getJson(admin, "/api/rentals/" + rental), "plannedEndAt"));

        // платёж 10000 (3 недели + 1000) со стратегией «уменьшить следующие»:
        // остаток 29000 размазывается по 12 оставшимся неделям → 2416 (последний 2424)
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":10000,\"accountId\":\"" + account + "\","
                                + "\"overpaymentStrategy\":\"reduce_next\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule[0].status").value("paid"))
                .andExpect(jsonPath("$.schedule[1].status").value("next"))
                .andExpect(jsonPath("$.schedule[1].amount").value(2416))
                .andExpect(jsonPath("$.schedule[1].paidPart").value(0))
                .andExpect(jsonPath("$.schedule[12].amount").value(2424))
                .andExpect(jsonPath("$.schedule.length()").value(13));

        // срок не изменился (даты хвоста прежние); plannedEndAt мог уехать на доли секунды
        // из-за сдвига при выдаче — сравниваем с точностью до секунды
        Instant endAfter = Instant.parse(extract(getJson(admin, "/api/rentals/" + rental), "plannedEndAt"));
        assertThat(Math.abs(endAfter.getEpochSecond() - endBefore.getEpochSecond())).isLessThanOrEqualTo(1);

        String body = getJson(admin, "/api/rentals/" + rental);
        assertThat(sumScheduleRemaining(body)).isEqualTo(39000 - 10000);

        // стратегия для обычной аренды → 409
        String bike2 = createBike(admin, account, "VIN-BO3R");
        String rentRental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"duration\":1,\"durationUnit\":\"day\","
                                + "\"items\":[{\"assetId\":\"" + bike2 + "\",\"rate\":1000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + rentRental + "/payments",
                        "{\"amount\":500,\"accountId\":\"" + account + "\","
                                + "\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void buyoutPriceEditableAndRecalcsSchedule() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Скидка");
        String bike = createBike(admin, account, "VIN-BO4");
        String rental = createBuyout(admin, customer, bike, 39000, 13);

        // черновик: договорились о скидке — график перегенерирован (13 × 2000)
        mvc.perform(patch("/api/rentals/" + rental)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyoutPrice\":26000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buyoutPrice").value(26000))
                .andExpect(jsonPath("$.amount").value(26000))
                .andExpect(jsonPath("$.schedule.length()").value(13))
                .andExpect(jsonPath("$.schedule[0].amount").value(2000))
                .andExpect(jsonPath("$.schedule[12].amount").value(2000));

        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":6000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());

        // сумма меньше уже оплаченного → 409
        mvc.perform(patch("/api/rentals/" + rental)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyoutPrice\":5000}"))
                .andExpect(status().isConflict());

        // активная: оплачено 6000 (3 платежа по 2000); наступившая погашенная неделя —
        // история, остаток 33000 − 6000 = 27000 размазан по 12 оставшимся неделям → 2250
        mvc.perform(patch("/api/rentals/" + rental)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyoutPrice\":33000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule[0].status").value("paid"))
                .andExpect(jsonPath("$.schedule[1].status").value("next"))
                .andExpect(jsonPath("$.schedule[1].amount").value(2250))
                .andExpect(jsonPath("$.schedule.length()").value(13));
        assertThat(sumScheduleRemaining(getJson(admin, "/api/rentals/" + rental)))
                .isEqualTo(33000 - 6000);
    }

    @Test
    void fullPaymentCompletesBuyoutAndAssetsBecomeBoughtOut() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Полный");
        String bike = createBike(admin, account, "VIN-BO5");
        // комплектная АКБ — сразу смонтирована на велосипеде, в выкуп едет за 0 ₽
        String battery = extract(postJson(admin, "/api/assets",
                        "{\"type\":\"battery\",\"inventoryNumber\":\"BAT-BO5\","
                                + "\"purchasePrice\":0,\"bundledBikeId\":\"" + bike + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)); // байк + комплектная АКБ

        // не полностью оплачено → завершить нельзя
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":38000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isConflict());

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":1000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule[12].status").value("paid"));

        // выкуп завершён: техника НЕ возвращается в парк — уходит клиенту (вся, включая АКБ)
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed"))
                .andExpect(jsonPath("$.amount").value(39000));
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.status").value("bought_out"));
        mvc.perform(get("/api/assets/" + battery + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.status").value("bought_out"));
        // выкупленный байк нельзя снова сдать или списать
        mvc.perform(get("/api/assets?status=available").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$[?(@.id == '" + bike + "')]").doesNotExist());
        postJson(admin, "/api/assets/" + bike + "/write-off",
                        "{\"reason\":\"broken\"}")
                .andExpect(status().isConflict());
    }

    @Test
    void terminationReturnsAssetsWithoutRefund() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Расторжение");
        String bike = createBike(admin, account, "VIN-BO6");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":6000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());

        // возврат денег при расторжении выкупа запрещён
        postJson(admin, "/api/rentals/" + rental + "/early-return",
                        "{\"refundAmount\":6000,\"refundAccountId\":\"" + account + "\"}")
                .andExpect(status().isConflict());

        // расторжение: техника обратно в парк, внесённое остаётся выручкой (amount = оплачено)
        postJson(admin, "/api/rentals/" + rental + "/early-return", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("completed_early"))
                .andExpect(jsonPath("$.paidAmount").value(6000))
                .andExpect(jsonPath("$.amount").value(6000));
        mvc.perform(get("/api/assets/" + bike + "/detail").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.asset.status").value("available"));
    }

    @Test
    void dashboardShowsOverdueAndUpcomingPayments() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Дашборд");

        // просрочка: платёж был вчера и раньше, денег нет
        String bike1 = createBike(admin, account, "VIN-BO7");
        String overdueRental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\","
                                + "\"buyoutPrice\":39000,\"termWeeks\":13,"
                                + "\"startAt\":\"" + Instant.now().minus(10, ChronoUnit.DAYS) + "\","
                                + "\"items\":[{\"assetId\":\"" + bike1 + "\",\"rate\":3000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + overdueRental + "/issue",
                        "{\"date\":\"" + Instant.now().minus(10, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isOk());

        // к оплате скоро: первый платёж внесён, следующий — завтра
        String bike2 = createBike(admin, account, "VIN-BO8");
        String soonRental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\","
                                + "\"buyoutPrice\":39000,\"termWeeks\":13,"
                                + "\"startAt\":\"" + Instant.now().minus(6, ChronoUnit.DAYS) + "\","
                                + "\"items\":[{\"assetId\":\"" + bike2 + "\",\"rate\":3000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
        postJson(admin, "/api/rentals/" + soonRental + "/payments",
                        "{\"amount\":3000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/rentals/" + soonRental + "/issue",
                        "{\"date\":\"" + Instant.now().minus(6, ChronoUnit.DAYS) + "\"}")
                .andExpect(status().isOk());

        String dashboard = getJson(admin, "/api/dashboard");
        assertThat(dashboard).containsSubsequence("\"overdue\":[");
        assertThat(extractIds(dashboard, "overdue")).contains(overdueRental);
        assertThat(extractIds(dashboard, "endingSoon")).contains(soonRental);
        assertThat(extractIds(dashboard, "overdue")).doesNotContain(soonRental);
    }

    // --- регрессия: переплата со стратегией «поглощается» перестройкой графика (строки
    // удаляются/уменьшаются), и поглощённые деньги НЕ должны заново гасить новые строки
    // при следующих платежах (бюджет FIFO = оплачено − absorbed). Инвариант после КАЖДОЙ
    // операции: остаток по графику = buyoutPrice − оплачено.

    @Test
    void shortenThenPlainPaymentsKeepInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Сокращ+Обычн");
        String bike = createBike(admin, account, "VIN-BR1");
        String rental = createBuyout(admin, customer, bike, 52000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());

        // платёж на 5 недель, сокращаем срок: 2 оплаченные авансом недели поглощаются
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":20000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(9));
        assertRemaining(admin, rental, 52000 - 20000);

        // обычный платёж БЕЗ стратегии: поглощённые 16000 не должны «воскреснуть»
        // и погасить новые строки — остаток строго 52000 − 36000
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":16000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 52000 - 36000);

        // выкуп не завершается, пока не внесена ВСЯ сумма выкупа
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isConflict());

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":16000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(52000));
        assertRemaining(admin, rental, 0);
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}").andExpect(status().isOk());
    }

    @Test
    void mixedStrategiesSequenceKeepsInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Микс");
        String bike = createBike(admin, account, "VIN-BR2");
        String rental = createBuyout(admin, customer, bike, 52000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());

        // reduce: остаток 42000 поровну по 12 слотам (3500)
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":10000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"reduce_next\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(13))
                .andExpect(jsonPath("$.schedule[1].amount").value(3500));
        assertRemaining(admin, rental, 42000);

        // shorten: платёж теперь 3500, график со следующей недели, 10 строк хвоста
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":10000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(11));
        assertRemaining(admin, rental, 32000);

        // ещё shorten: 4 строки хвоста (3×3500 + 1500)
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":20000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(5));
        assertRemaining(admin, rental, 12000);

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":12000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 0);
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}").andExpect(status().isOk());
    }

    @Test
    void shortenTwiceInARowKeepsInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Дважды");
        String bike = createBike(admin, account, "VIN-BR3");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":6000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(12));
        assertRemaining(admin, rental, 33000);

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":6000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(10));
        assertRemaining(admin, rental, 27000);
    }

    @Test
    void reduceThenOverpayBeyondScheduleCompletes() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Сверхграфика");
        String bike = createBike(admin, account, "VIN-BR4");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":10000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"reduce_next\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 29000);

        // платёж больше, чем осталось по уменьшенному графику: покрытие упирается в суммы
        // строк, остаток 0, а завершение проверяет деньги (оплачено >= buyoutPrice)
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":40000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(50000));
        assertRemaining(admin, rental, 0);
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(50000));
    }

    @Test
    void overpayBeyondTotalWithoutStrategyCompletes() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Сверху");
        String bike = createBike(admin, account, "VIN-BR5");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":60000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 0);
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}").andExpect(status().isOk());
    }

    @Test
    void paymentEditAfterRebuildKeepsInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Правка");
        String bike = createBike(admin, account, "VIN-BR6");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 30000);

        // правка суммы оплаты из карточки (9000 → 12000): перестройка не откатывается,
        // покрытие переразносится от «оплачено − absorbed» → остаток 39000 − 12000
        String payment = extract(getJson(admin, "/api/finance/transactions?rentalId=" + rental), "id");
        mvc.perform(patch("/api/finance/transactions/" + payment)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\":12000}"))
                .andExpect(status().isOk());
        assertRemaining(admin, rental, 39000 - 12000);
    }

    @Test
    void paymentDeleteAfterRebuildDoesNotRollbackButPriceEditRestores() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Удаление");
        String bike = createBike(admin, account, "VIN-BR7");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated());

        // удаление оплаты: сделанное сокращение срока НЕ откатывается — остаток = сумме
        // текущих строк (33000), хотя по деньгам не оплачено ничего
        String payment = extract(getJson(admin, "/api/finance/transactions?rentalId=" + rental), "id");
        mvc.perform(delete("/api/finance/transactions/" + payment)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isNoContent());
        String body = getJson(admin, "/api/rentals/" + rental);
        assertThat(extractInt(body, "paidAmount")).isZero();
        assertThat(sumScheduleRemaining(body)).isEqualTo(33000);

        // ручное восстановление: переставить сумму выкупа (даже на ту же) — хвост
        // пересчитывается от неё, остаток снова равен цене выкупа
        mvc.perform(patch("/api/rentals/" + rental)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyoutPrice\":39000}"))
                .andExpect(status().isOk());
        assertRemaining(admin, rental, 39000);
    }

    @Test
    void shortenInDraftBeforeIssueKeepsInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Черновик");
        String bike = createBike(admin, account, "VIN-BR8");
        // черновик с началом через 10 дней: ни одна строка ещё не «наступила»
        String rental = extract(postJson(admin, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\","
                                + "\"buyoutPrice\":39000,\"termWeeks\":13,"
                                + "\"startAt\":\"" + Instant.now().plus(10, ChronoUnit.DAYS) + "\","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":3000}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");

        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.schedule.length()").value(10))
                .andExpect(jsonPath("$.schedule[0].status").value("next"));
        assertRemaining(admin, rental, 39000 - 9000);
    }

    @Test
    void priceEditActiveAfterShortenKeepsInvariant() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп ЦенаПосле");
        String bike = createBike(admin, account, "VIN-BR9");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 30000);

        // подняли цену выкупа: хвост (10 слотов) пересчитан на 45000 − 9000 = 36000 → 3600
        mvc.perform(patch("/api/rentals/" + rental)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"buyoutPrice\":45000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schedule.length()").value(11))
                .andExpect(jsonPath("$.schedule[1].amount").value(3600));
        assertRemaining(admin, rental, 45000 - 9000);

        // доплата до новой цены закрывает выкуп ровно в ноль
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":36000,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        assertRemaining(admin, rental, 0);
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}").andExpect(status().isOk());
    }

    @Test
    void completeBlockedUntilFullPaymentAfterRebuilds() throws Exception {
        String admin = login();
        String account = accountId(admin);
        String customer = createCustomer(admin, "Выкуп Блокировка");
        String bike = createBike(admin, account, "VIN-BR10");
        String rental = createBuyout(admin, customer, bike, 39000, 13);
        postJson(admin, "/api/rentals/" + rental + "/issue", "{}").andExpect(status().isOk());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":9000,\"accountId\":\"" + account
                                + "\",\"overpaymentStrategy\":\"shorten_term\"}")
                .andExpect(status().isCreated());

        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isConflict());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":29999,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated());
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}")
                .andExpect(status().isConflict());
        postJson(admin, "/api/rentals/" + rental + "/payments",
                        "{\"amount\":1,\"accountId\":\"" + account + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.paidAmount").value(39000));
        postJson(admin, "/api/rentals/" + rental + "/complete", "{}").andExpect(status().isOk());
    }

    // --- helpers ---

    private String createBuyout(String token, String customer, String bike, int price, int weeks)
            throws Exception {
        return extract(postJson(token, "/api/rentals",
                        "{\"customerId\":\"" + customer + "\",\"kind\":\"rent_to_own\","
                                + "\"buyoutPrice\":" + price + ",\"termWeeks\":" + weeks + ","
                                + "\"items\":[{\"assetId\":\"" + bike + "\",\"rate\":"
                                + price / weeks + "}]}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private String createCustomer(String token, String name) throws Exception {
        return extract(postJson(token, "/api/customers",
                        "{\"fullName\":\"" + name + "\",\"phone\":\"+7 900 100-20-30\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private String createBike(String token, String account, String inventoryNumber) throws Exception {
        return extract(postJson(token, "/api/assets",
                        "{\"type\":\"bike\",\"inventoryNumber\":\"" + inventoryNumber
                                + "\",\"purchasePrice\":50000,\"purchaseAccountId\":\"" + account
                                + "\",\"purchasedAt\":\"2024-01-15T10:00:00Z\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "id");
    }

    private String accountId(String token) throws Exception {
        return extract(getJson(token, "/api/finance/accounts"), "id");
    }

    /** Остаток по графику: Σ(amount − paidPart) по строкам schedule. */
    private static int sumScheduleRemaining(String rentalJson) {
        Matcher matcher = Pattern.compile("\"amount\":(\\d+),\"paidPart\":(\\d+)").matcher(rentalJson);
        int sum = 0;
        while (matcher.find()) {
            sum += Integer.parseInt(matcher.group(1)) - Integer.parseInt(matcher.group(2));
        }
        return sum;
    }

    /** Инвариант договора: остаток по графику = цена выкупа − оплачено. */
    private void assertRemaining(String token, String rentalId, int expected) throws Exception {
        assertThat(sumScheduleRemaining(getJson(token, "/api/rentals/" + rentalId)))
                .isEqualTo(expected);
    }

    /** Числовое поле верхнего уровня в JSON (например, paidAmount). */
    private static int extractInt(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":(\\d+)").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Поле " + field + " не найдено в " + json);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /** id аренд из конкретного блока дашборда ("overdue" / "endingSoon"). */
    private static java.util.List<String> extractIds(String dashboardJson, String section) {
        String marker = "\"" + section + "\":[";
        int start = dashboardJson.indexOf(marker) + marker.length();
        int end = dashboardJson.indexOf("]", start);
        java.util.List<String> ids = new java.util.ArrayList<>();
        Matcher matcher = Pattern.compile("\"id\":\"([^\"]+)\"")
                .matcher(dashboardJson.substring(start, end));
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    /** Первое значение поля "dueDate" в JSON. */
    private static String extractDates(String json, String field) {
        return extract(json, field);
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
        return mvc.perform(post(path)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String getJson(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private static String extract(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(json);
        if (!matcher.find()) {
            throw new IllegalStateException("Поле " + field + " не найдено в " + json);
        }
        return matcher.group(1);
    }
}
