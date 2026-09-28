package com.guicedee.activitymaster.payments;

import io.smallrye.mutiny.Uni;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

/** Trusted adapter. Provider calls run outside the ActivityMaster transaction. */
public interface PaymentGateway {
    /** MUST use attempt.id as provider idempotency key, recovering the same payment after uncertain outcomes. */
    Uni<Checkout> start(Attempt attempt);
    /** Verify raw signature/authenticity, merchant and final payment status before returning confirmation. */
    Uni<Confirmation> verify(String merchant, Callback callback);
}
