package io.crm.dashboard.dto;

import java.math.BigDecimal;

public record DashboardResponse(
        long totalContacts,
        long openLeads,
        long openDeals,
        long wonDeals,
        long lostDeals,
        BigDecimal openPipelineValue
) {}
