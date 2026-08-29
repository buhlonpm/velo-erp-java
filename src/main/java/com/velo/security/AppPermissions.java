package com.velo.security;

import java.util.Set;

/**
 * Реестр гранулярных прав (в дополнение к ролям).
 * Хранятся строками в таблице user_permissions.
 */
public final class AppPermissions {

    /** Просмотр финансов: счета, остатки, операции + правка/удаление операций. */
    public static final String FINANCE_VIEW = "finance:view";

    /** Все известные права — для валидации входящих значений. */
    public static final Set<String> ALL = Set.of(FINANCE_VIEW);

    private AppPermissions() {
    }
}
