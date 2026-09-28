package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.wallet.WalletIdentity;
import com.guicedee.activitymaster.wallet.WalletSystem;
import com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorAuthority;
import com.guicedee.activitymaster.fsdm.db.entities.involvedparty.InvolvedParty;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.List;

/** Current payment plugin admission. Every service call also enters Wallet Master's actor/row authority. */
final class PaymentAccess {
    private final FsdmBehaviorAuthority behaviors = new FsdmBehaviorAuthority();
    Uni<Void> check(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity,
                    String provider, String action) {
        if (!List.of("payment.create", "payment.read", "payment.confirm").contains(action))
            throw new IllegalArgumentException("Unknown payment behavior");
        if (!PaymentSystem.NAME.equals(system.getName()) || !identity.enterpriseId().equals(system.getEnterprise().getId()))
            return denied();
        return plugin(session, system, identity, provider, action);
    }
    Uni<Void> depositAdmission(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity) {
        if (!WalletSystem.NAME.equals(system.getName()) || !identity.enterpriseId().equals(system.getEnterprise().getId())) return denied();
        return plugin(session, system, identity, identity.providerId(), "wallet.post")
            .chain(() -> plugin(session, system, identity, identity.providerId(), "wallet.deposit"));
    }
    private Uni<Void> plugin(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, String provider, String action) {
        return behaviors.check(session, system.getId(), identity.enterpriseId(), identity.actor(),
                    identity.context(), identity.identityToken(), provider, action)
                .chain(() -> session.createNativeQuery("""
                    select 1 from party.involvedparty p join dbo.activeflag f on f.activeflagid=p.activeflagid
                    where p.involvedpartyid=:actor and p.enterpriseid=:enterprise and f.allowaccess=1
                    and p.effectivefromdate<=statement_timestamp() and p.effectivetodate>statement_timestamp() for share of p,f
                    """, Integer.class).setParameter("actor", identity.partyId()).setParameter("enterprise", identity.enterpriseId())
                    .getResultList().chain(rows -> rows.isEmpty() ? denied() : Uni.createFrom().voidItem()))
                .chain(() -> new InvolvedParty().setId(identity.partyId()).canRead(session, system, identity.tokens()))
                .chain(PaymentRows::require);
    }
    private static <T> Uni<T> denied() { return Uni.createFrom().failure(new SecurityException("Payment access denied")); }
}
