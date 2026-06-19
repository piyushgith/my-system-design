package com.ecommerce.order.payment;

import com.ecommerce.order.domain.Order;

/**
 * Strategy seam for payment handling. The MVP ships COD only, but routing checkout
 * through this interface means adding a gateway (card, UPI, wallet) is a new
 * implementation rather than an edit to the checkout flow.
 */
public interface PaymentProcessor {

    /** Payment method identifier this processor handles (e.g. "COD"). */
    String method();

    /**
     * Authorize/initiate payment for the order. Implementations may throw to abort
     * checkout. COD is authorized implicitly (collected on delivery).
     */
    void authorize(Order order);
}
