package com.fooddelivery.delivery.service.dto;

import java.math.BigDecimal;

public record PartnerLocationResponse(BigDecimal latitude, BigDecimal longitude, boolean available) {}
