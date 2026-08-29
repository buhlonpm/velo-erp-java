package com.velo.config;

import com.velo.finance.AccountType;
import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceAccount;
import com.velo.finance.FinanceAccountRepository;
import com.velo.finance.FinanceCategory;
import com.velo.finance.FinanceCategoryRepository;
import com.velo.user.Role;
import com.velo.user.User;
import com.velo.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Создаёт первого администратора и дефолтные финансовые справочники,
 * если соответствующие таблицы пусты.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AppProperties properties;
    private final FinanceAccountRepository accountRepository;
    private final FinanceCategoryRepository categoryRepository;

    @Override
    public void run(ApplicationArguments args) {
        seedAdmin();
        seedFinance();
    }

    private void seedAdmin() {
        if (userRepository.count() > 0) {
            return;
        }
        AppProperties.Admin adminProps = properties.getAdmin();
        User admin = new User();
        admin.setEmail(adminProps.getEmail());
        admin.setFullName(adminProps.getFullName());
        admin.setPasswordHash(passwordEncoder.encode(adminProps.getPassword()));
        admin.setRole(Role.ADMIN);
        userRepository.save(admin);
        log.info("Создан первый администратор: {}", admin.getEmail());
    }

    private void seedFinance() {
        if (accountRepository.count() == 0) {
            FinanceAccount cash = new FinanceAccount();
            cash.setName("Касса (наличные)");
            cash.setType(AccountType.CASH);
            accountRepository.save(cash);
            log.info("Создан счёт по умолчанию: {}", cash.getName());
        }
        if (categoryRepository.count() == 0) {
            Map<String, CategoryKind> defaults = Map.ofEntries(
                    Map.entry("Оплата аренды", CategoryKind.INCOME),
                    Map.entry("Залог", CategoryKind.INCOME),
                    Map.entry("Штраф за повреждения", CategoryKind.INCOME),
                    Map.entry("Обслуживание и ремонт", CategoryKind.EXPENSE),
                    Map.entry("Аренда помещения", CategoryKind.EXPENSE),
                    Map.entry("Зарплата", CategoryKind.EXPENSE),
                    Map.entry("Реклама", CategoryKind.EXPENSE));
            List<FinanceCategory> categories = defaults.entrySet().stream()
                    .map(entry -> {
                        FinanceCategory category = new FinanceCategory();
                        category.setName(entry.getKey());
                        category.setKind(entry.getValue());
                        return category;
                    })
                    .toList();
            categoryRepository.saveAll(categories);
            log.info("Созданы статьи прихода/расхода по умолчанию: {} шт.", categories.size());
        }
    }
}
