package com.guicedee.activitymaster.payments;

import com.google.inject.ImplementedBy;
import com.guicedee.activitymaster.wallet.WalletIdentity;
import io.smallrye.mutiny.Uni;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

/** Host policy and fresh canonical identity resolution. Never bind browser-supplied identity fields. */
@ImplementedBy(PaymentHost.Deny.class)
public interface PaymentHost {
    Uni<Route> route(WalletIdentity identity, Deposit request);
    Uni<WalletIdentity> currentActor(Attempt attempt);
    final class Deny implements PaymentHost {
        public Uni<Route> route(WalletIdentity identity, Deposit request) { return denied(); }
        public Uni<WalletIdentity> currentActor(Attempt attempt) { return denied(); }
        private static <T> Uni<T> denied() { return Uni.createFrom().failure(new SecurityException("Payment host binding required")); }
    }
}
