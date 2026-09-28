package com.guicedee.activitymaster.payments;

import com.google.inject.Inject;
import com.guicedee.activitymaster.fsdm.client.services.ISystemsService;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.wallet.*;
import com.guicedee.activitymaster.wallet.WalletModels.*;
import com.guicedee.activitymaster.fsdm.db.entities.arrangement.Arrangement;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

public final class PaymentService implements IPaymentService<PaymentService> {
    private final IWalletService<?> wallets;
    private final ISystemsService<?> systems;
    private final PaymentStore store = new PaymentStore();
    private final PaymentAccess access = new PaymentAccess();
    @Inject public PaymentService(IWalletService<?> wallets, ISystemsService<?> systems) {
        this.wallets=wallets; this.systems=systems;
    }
    public Uni<Attempt> prepare(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, Deposit deposit) {
        requireTransaction(session);
        Attempt intent = new Attempt(intentId(identity.enterpriseId(), deposit.operationKey()), Actor.from(identity), route, deposit, null, null);
        intent.movement(); // Validate distinct arrangements before persisting intent.
        return access.check(session, system, identity, route.paymentProviderId(), "payment.create")
            .chain(() -> depositAdmission(session, system, identity, route, deposit))
            .chain(() -> readWallet(session, system, identity, deposit))
            .chain(() -> store.create(session, system, identity, intent));
    }
    public Uni<Attempt> get(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, UUID id) {
        requireTransaction(session);
        return authorized(session, system, identity, route, id, "payment.read")
            .call(attempt -> readWallet(session, system, identity, attempt.deposit()));
    }
    public Uni<Attempt> bind(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, UUID id, String reference) {
        requireTransaction(session); identifier(reference);
        return authorized(session, system, identity, route, id, "payment.create")
            .call(attempt -> depositAdmission(session, system, identity, route, attempt.deposit()))
            .call(attempt -> readWallet(session, system, identity, attempt.deposit()))
            .chain(attempt -> store.bind(session, system, identity, attempt, reference));
    }
    public Uni<Receipt> confirm(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, Confirmation confirmation) {
        requireTransaction(session);
        return authorized(session, system, identity, route, confirmation.paymentId(), "payment.confirm")
            .invoke(attempt -> {
                if (!attempt.route().merchant().equals(confirmation.merchant()) || !attempt.deposit().amount().equals(confirmation.amount())
                    || !attempt.deposit().unit().equals(confirmation.unit())) throw new SecurityException("Payment confirmation mismatch");
            })
            .chain(attempt -> store.bind(session, system, identity, attempt, confirmation.reference()))
            // Re-enter Wallet Master even for settled retries: current wallet grants/row access must still pass.
            .chain(attempt -> systems.findSystem(session, system.getEnterprise(), WalletSystem.NAME)
                .chain(walletSystem -> wallets.move(session, walletSystem, identity, Action.DEPOSIT, attempt.movement()))
                .call(receipt -> store.settle(session, system, identity, attempt, receipt.eventId())));
    }
    private Uni<Attempt> authorized(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, UUID id, String behavior) {
        return access.check(session, system, identity, route.paymentProviderId(), behavior)
            .chain(() -> store.load(session, system, id, true))
            .invoke(attempt -> {
                if (!attempt.actor().equals(Actor.from(identity)) || !attempt.route().equals(route))
                    throw new SecurityException("Payment actor or route mismatch");
            }).call(attempt -> store.authorize(session, system, identity, attempt, !behavior.equals("payment.read")));
    }
    private Uni<Void> readWallet(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Deposit deposit) {
        return systems.findSystem(session, system.getEnterprise(), WalletSystem.NAME)
            .chain(walletSystem -> wallets.balance(session, walletSystem, identity, deposit.walletId(), deposit.unit())).replaceWithVoid();
    }
    private Uni<Void> depositAdmission(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, Deposit deposit) {
        return systems.findSystem(session, system.getEnterprise(), WalletSystem.NAME).chain(walletSystem ->
            access.depositAdmission(session, walletSystem, identity)
                .chain(() -> arrangementAdmission(session, identity, route.clearingId(), "Wallet Clearing"))
                .chain(() -> arrangementAdmission(session, identity, deposit.walletId(), "Wallet"))
                .chain(() -> new Arrangement().setId(route.clearingId()).canWrite(session, walletSystem, identity.tokens())).chain(PaymentRows::require)
                .chain(() -> new Arrangement().setId(deposit.walletId()).canWrite(session, walletSystem, identity.tokens())).chain(PaymentRows::require));
    }
    private Uni<Void> arrangementAdmission(Mutiny.StatelessSession session, WalletIdentity identity, UUID id, String type) {
        // Admission snapshot only: Wallet Master repeats its full checks under movement locks at settlement.
        return session.createNativeQuery("""
            select r.arrangementid from arrangement.arrangement r
            join dbo.activeflag f on f.activeflagid=r.activeflagid
            where r.arrangementid=:id and r.enterpriseid=:enterprise and f.allowaccess=1
            and r.effectivefromdate<=statement_timestamp() and r.effectivetodate>statement_timestamp()
            and exists (select 1 from arrangement.arrangementxarrangementtype x
                join arrangement.arrangementtype t on t.arrangementtypeid=x.arrangementtypeid
                where x.arrangementid=r.arrangementid and x.enterpriseid=:enterprise and t.enterpriseid=:enterprise
                and t.arrangementtypename=:type and x.effectivefromdate<=statement_timestamp() and x.effectivetodate>statement_timestamp())
            and (:work=true or exists (select 1 from arrangement.arrangementxinvolvedparty p
                where p.arrangementid=r.arrangementid and p.enterpriseid=:enterprise and p.involvedpartyid=:actor
                and p.effectivefromdate<=statement_timestamp() and p.effectivetodate>statement_timestamp()))
            """,UUID.class).setParameter("id",id).setParameter("enterprise",identity.enterpriseId()).setParameter("type",type)
            .setParameter("work",identity.context().realm()==com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Realm.WORK)
            .setParameter("actor",identity.partyId()).getResultList().chain(rows -> PaymentRows.require(rows.size()==1));
    }
    private static void requireTransaction(Mutiny.StatelessSession session) {
        if (session.currentTransaction() == null || session.currentTransaction().isMarkedForRollback())
            throw new IllegalStateException("Active stateless transaction required");
    }
}
