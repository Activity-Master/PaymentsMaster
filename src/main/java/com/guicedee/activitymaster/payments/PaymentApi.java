package com.guicedee.activitymaster.payments;

import com.google.inject.Inject;
import com.guicedee.activitymaster.wallet.*;
import com.guicedee.activitymaster.wallet.WalletModels.Receipt;
import io.smallrye.mutiny.Uni;
import java.util.UUID;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

/** Authenticated host API. All successful results follow commit; no detached subscribers. */
public final class PaymentApi {
    private final WalletIdentityProvider identities;
    private final PaymentHost host;
    private final PaymentGateways gateways;
    private final IPaymentService<?> service;
    private final PaymentTransactions transactions;
    private final PaymentStore store = new PaymentStore();
    @Inject public PaymentApi(WalletIdentityProvider identities, PaymentHost host, PaymentGateways gateways,
                              IPaymentService<?> service, PaymentTransactions transactions) {
        this.identities=identities; this.host=host; this.gateways=gateways; this.service=service; this.transactions=transactions;
    }
    public Uni<Started> start(String enterprise, Deposit deposit) {
        return current().chain(identity -> route(identity, deposit)
            .chain(route -> transactions.run(enterprise, (session, system) -> service.prepare(session, system, identity, route, deposit))))
            .chain(attempt -> {
                if (attempt.eventId() != null) return Uni.createFrom().item(new Started(attempt.id(), attempt.status(), null));
                // The intent transaction is committed before this network operation is subscribed.
                return Uni.createFrom().deferred(() -> gateways.require(attempt.route().gateway()).start(attempt))
                    .onItem().ifNull().failWith(() -> new IllegalStateException("Provider checkout missing"))
                    .chain(checkout -> current().chain(identity -> route(identity, deposit)
                        .chain(route -> transactions.run(enterprise, (session, system) ->
                            service.bind(session, system, identity, route, attempt.id(), checkout.reference())))
                        .map(saved -> new Started(saved.id(), saved.status(), checkout))));
            });
    }
    public Uni<Attempt> get(String enterprise, UUID operationKey) {
        return current().chain(identity -> transactions.run(enterprise, (session, system) -> {
            if (!identity.enterpriseId().equals(system.getEnterprise().getId())) return denied();
            return store.load(session, system, intentId(identity.enterpriseId(), operationKey), false);
        }).invoke(attempt -> requireActor(attempt, identity))
            .chain(attempt -> route(identity, attempt.deposit()).chain(route -> transactions.run(enterprise,
                (session, system) -> service.get(session, system, identity, route, attempt.id())))));
    }
    /** Only call from a configured provider callback/reconciliation route, never a browser success redirect. */
    public Uni<Receipt> confirm(String enterprise, String gateway, String merchant, Callback callback) {
        return Uni.createFrom().deferred(() -> gateways.require(gateway).verify(merchant, callback))
            .onItem().ifNull().failWith(() -> new SecurityException("Verified payment confirmation required"))
            .invoke(confirmation -> {
                if (!merchant.equals(confirmation.merchant())) throw new SecurityException("Payment merchant mismatch");
            })
            .chain(confirmation -> transactions.run(enterprise, (session, system) ->
                store.load(session, system, confirmation.paymentId(), false))
                .invoke(attempt -> {
                    if (!attempt.route().gateway().equals(gateway) || !attempt.route().merchant().equals(merchant))
                        throw new SecurityException("Payment callback route mismatch");
                })
                .chain(attempt -> Uni.createFrom().deferred(() -> host.currentActor(attempt))
                    .onItem().ifNull().failWith(() -> new SecurityException("Current payment actor required"))
                    .invoke(identity -> requireActor(attempt, identity))
                    .chain(identity -> route(identity, attempt.deposit()).chain(route -> transactions.run(enterprise,
                        (session, system) -> service.confirm(session, system, identity, route, confirmation))))));
    }
    private Uni<WalletIdentity> current() {
        return Uni.createFrom().deferred(identities::current)
            .onItem().ifNull().failWith(() -> new SecurityException("Authenticated payment identity required"));
    }
    private Uni<Route> route(WalletIdentity identity, Deposit deposit) {
        return Uni.createFrom().deferred(() -> host.route(identity, deposit))
            .onItem().ifNull().failWith(() -> new SecurityException("Authorized payment route required"));
    }
    private static void requireActor(Attempt attempt, WalletIdentity identity) {
        if (!attempt.actor().equals(Actor.from(identity))) throw new SecurityException("Payment actor/context mismatch");
    }
    private static <T> Uni<T> denied() { return Uni.createFrom().failure(new SecurityException("Payment enterprise mismatch")); }
}
