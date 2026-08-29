package com.velo.config;

import com.velo.asset.AssetStatus;
import com.velo.asset.AssetType;
import com.velo.finance.AccountType;
import com.velo.finance.CategoryKind;
import com.velo.rental.RentalKind;
import com.velo.tariff.TariffUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Биндинг enum'ов из @RequestParam по их json-значению ("income" → INCOME).
 * Дефолтный конвертер Spring матчит по name() и падает на lower-case значениях.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, CategoryKind.class, CategoryKind::fromValue);
        registry.addConverter(String.class, AccountType.class, AccountType::fromValue);
        registry.addConverter(String.class, AssetType.class, AssetType::fromValue);
        registry.addConverter(String.class, AssetStatus.class, AssetStatus::fromValue);
        registry.addConverter(String.class, RentalKind.class, RentalKind::fromValue);
        registry.addConverter(String.class, TariffUnit.class, TariffUnit::fromValue);
    }
}
