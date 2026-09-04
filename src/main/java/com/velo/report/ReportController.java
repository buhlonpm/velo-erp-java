package com.velo.report;

import com.velo.report.dto.PnlReportResponse;
import com.velo.security.AppPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Отчёты — только тем, кто видит финансы (finance:view или ADMIN). */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private static final String CAN_VIEW_FINANCES =
            "hasRole('ADMIN') or hasAuthority('" + AppPermissions.FINANCE_VIEW + "')";

    private final ReportService reportService;

    /** Кассовый P&L за период (календарные даты, включительно), опц. фильтр по счёту.
     *  Без from — «за всё время»: старт от даты первой операции по кассе. */
    @GetMapping("/pnl")
    @PreAuthorize(CAN_VIEW_FINANCES)
    public PnlReportResponse pnl(@RequestParam(required = false) LocalDate from,
                                 @RequestParam LocalDate to,
                                 @RequestParam(required = false) UUID accountId) {
        return reportService.pnl(from, to, accountId);
    }
}
