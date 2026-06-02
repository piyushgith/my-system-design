package com.fooddelivery.common;

public final class FeeConstants {

    public static final long DELIVERY_FEE_PAISE = 4000L;    // Rs 40
    public static final long PLATFORM_FEE_PAISE = 200L;     // Rs 2
    public static final long PARTNER_EARNING_PAISE = 3000L; // Rs 30

    /** Added to restaurant prep time to estimate total delivery time. */
    public static final int TRANSIT_BUFFER_MINUTES = 20;

    private FeeConstants() {}
}
