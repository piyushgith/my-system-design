package com.ecommerce.order.payment;

import com.ecommerce.order.domain.Order;
import org.springframework.stereotype.Component;

/** Cash-on-delivery: no upfront authorization; payment is collected at delivery. */
@Component
public class CodPaymentProcessor implements PaymentProcessor {

    public static final String METHOD = "COD";

    @Override
    public String method() {
        return METHOD;
    }

    @Override
    public void authorize(Order order) {
        // No-op: COD requires no payment authorization at checkout time.
    }
}
