package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.systems.*;
import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.client.IGuiceContext;
import com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorTaxonomy;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

/** Capability registration after Wallet taxonomy. DDL and actor grants remain managed host work. */
@SortedUpdate(sortOrder = 1300, taskCount = 1)
public final class PaymentSystemInstall implements ISystemUpdate {
    public Uni<Boolean> update(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        IClassificationDataConceptService<?> concepts=IGuiceContext.get(IClassificationDataConceptService.class);
        IEventService<?> events=IGuiceContext.get(IEventService.class);
        IClassificationService<?> classifications=IGuiceContext.get(IClassificationService.class);
        var master = IGuiceContext.get(PaymentSystem.class);
        return master.getSystem(session, enterprise).chain(system -> master.getSystemToken(session, enterprise).chain(token ->
            concepts.createDataConcept(session,
                EnterpriseClassificationDataConcepts.EventXEvent, "Event relationships", system, token)
            .chain(() -> FsdmBehaviorTaxonomy.ensure(session, system, token))
            .chain(() -> events.createEventType(session, "Payment Attempt", system, token))
            .chain(() -> {
                Uni<Void> chain = Uni.createFrom().voidItem();
                for (var entry : PaymentStore.TAXONOMY.entrySet()) {
                    for (String name : entry.getValue()) {
                        chain = chain.chain(() -> classifications.create(session,
                            name, name, entry.getKey(), system, token).replaceWithVoid());
                    }
                }
                return chain;
            })))
            .invoke(() -> logProgress(PaymentSystem.NAME, "Registered payment capability", 1)).replaceWith(Boolean.TRUE);
    }
}
