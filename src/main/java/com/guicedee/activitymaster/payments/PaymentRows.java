package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.abstraction.WarehouseSCDTable;
import com.guicedee.activitymaster.fsdm.db.entities.classifications.Classification;
import com.guicedee.activitymaster.wallet.WalletIdentity;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;

/** Secured FSDM writes on the caller's stateless transaction. */
final class PaymentRows {
    static <R extends WarehouseSCDTable<R, ?, ?, ?>> Uni<R> persist(
            Mutiny.StatelessSession session, R row, ISystems<?, ?> system, WalletIdentity identity) {
        row.setEnterpriseID(system.getEnterprise()).setSystemID(system).setOriginalSourceSystemID(system);
        IActiveFlagService<?> flags = IGuiceContext.get(IActiveFlagService.class);
        ISecurityTokenService<?> security = IGuiceContext.get(ISecurityTokenService.class);
        return flags.getActiveFlag(session, system.getEnterprise(), identity.tokens()).chain(flag -> {
            row.setActiveFlagID(flag);
            return row.builder(session).persist(row)
                .chain(() -> security.resolveDefaultGroupFolderTokens(session, system, identity.tokens()))
                .chain(groups -> row.createScopeRestrictedSecurity(session, system, system.getEnterprise(), flag, groups, null, identity.tokens()))
                .chain(() -> security.getSecurityToken(session, identity.identityToken(), system, identity.tokens())
                    .onItem().ifNull().failWith(() -> new SecurityException("Payment actor credential unavailable")))
                .chain(token -> {
                    // Intent terms and links are append-only through this service; the actor gets read access.
                    boolean event = row instanceof com.guicedee.activitymaster.fsdm.db.entities.events.Event;
                    return row.createSecurityGrant(session, system, system.getEnterprise(), flag,
                        token, event, event, false, true, identity.tokens());
                }).replaceWith(row);
        });
    }
    static Uni<Classification> role(Mutiny.StatelessSession session, ISystems<?, ?> system, String name, String concept) {
        return session.createNativeQuery("""
            select c.classificationid from classification.classification c
            join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid
            join dbo.activeflag f on f.activeflagid=c.activeflagid
            where c.classificationname=:name and d.classificationdataconceptname=:concept
            and c.enterpriseid=:enterprise and c.systemid=:system and f.allowaccess=1
            and c.effectivefromdate<=statement_timestamp() and c.effectivetodate>statement_timestamp() for share of c,d,f
            """, UUID.class).setParameter("name", name).setParameter("concept", concept)
            .setParameter("enterprise", system.getEnterprise().getId()).setParameter("system", system.getId()).getResultList()
            .chain(rows -> rows.size()==1 ? Uni.createFrom().item(new Classification().setId(rows.getFirst())) :
                Uni.createFrom().failure(new IllegalStateException("Payment taxonomy unavailable: " + name)));
    }
    static Uni<Void> lock(Mutiny.StatelessSession session, UUID id) {
        return session.createNativeQuery("select 1 from pg_advisory_xact_lock(hashtextextended(:key,0))", Integer.class)
            .setParameter("key", "payment:" + id).getSingleResult().replaceWithVoid();
    }
    static Uni<Void> require(boolean allowed) {
        return allowed ? Uni.createFrom().voidItem() : Uni.createFrom().failure(new SecurityException("Payment row access denied"));
    }
}
