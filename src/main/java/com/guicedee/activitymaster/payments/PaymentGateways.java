package com.guicedee.activitymaster.payments;

import com.google.inject.ImplementedBy;

/** Server-configured allowlist. Neither class names nor merchant secrets come from requests. */
@ImplementedBy(PaymentGateways.Deny.class)
public interface PaymentGateways {
    PaymentGateway require(String gateway);
    final class Deny implements PaymentGateways {
        public PaymentGateway require(String gateway) { throw new SecurityException("Payment gateway not configured"); }
    }
}
