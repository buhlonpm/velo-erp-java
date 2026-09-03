package com.velo.config;

import com.velo.finance.AccountType;
import com.velo.finance.CategoryKind;
import com.velo.finance.FinanceAccount;
import com.velo.finance.FinanceAccountRepository;
import com.velo.finance.FinanceCategory;
import com.velo.finance.FinanceCategoryRepository;
import com.velo.finance.SystemCategories;
import com.velo.user.Role;
import com.velo.user.User;
import com.velo.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

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
        // системные статьи — всегда гарантируем (нельзя удалить, доменный код ссылается по имени)
        SystemCategories.ALL.forEach((name, kind) -> ensureCategory(name, kind, true));
        // дефолтные пользовательские — можно удалять
        Map<String, CategoryKind> defaults = Map.of(
                "Чаевые", CategoryKind.INCOME,
                "Зарплата", CategoryKind.EXPENSE,
                "Реклама", CategoryKind.EXPENSE,
                "Аренда", CategoryKind.EXPENSE);
        defaults.forEach((name, kind) -> ensureCategory(name, kind, false));
    }

    private void ensureCategory(String name, CategoryKind kind, boolean system) {
        categoryRepository.findByNameAndKind(name, kind).ifPresentOrElse(existing -> {
            if (system && !existing.isSystem()) {
                existing.setSystem(true);
                categoryRepository.save(existing);
                log.info("Статья «{}» помечена системной", name);
            }
        }, () -> {
            FinanceCategory category = new FinanceCategory();
            category.setName(name);
            category.setKind(kind);
            category.setSystem(system);
            categoryRepository.save(category);
            log.info("Создана{} статья: {}", system ? " системная" : "", name);
        });
    }
}
