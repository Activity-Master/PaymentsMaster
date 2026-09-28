package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.SessionUtils;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.client.IGuiceContext;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

/** Top-level transaction boundary only. Provider I/O never runs in Work. */
public class PaymentTransactions {
    @FunctionalInterface public interface Work<T> { Uni<T> run(Mutiny.StatelessSession session, ISystems<?, ?> system); }
    public <T> Uni<T> run(String enterprise, Work<T> work) {
        if (enterprise == null || enterprise.isBlank()) return Uni.createFrom().failure(new IllegalArgumentException("Enterprise required"));
        return Uni.createFrom().deferred(() -> SessionUtils.withActivityMaster(enterprise, PaymentSystem.NAME,
            tuple -> IGuiceContext.get(PaymentSystem.class).getSystem(tuple.getItem1(), tuple.getItem2())
                .chain(system -> work.run(tuple.getItem1(), system))));
    }
}
