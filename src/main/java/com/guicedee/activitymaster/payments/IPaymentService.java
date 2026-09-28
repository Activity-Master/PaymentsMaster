package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.wallet.WalletIdentity;
import com.guicedee.activitymaster.wallet.WalletModels.Receipt;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.util.UUID;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

/** Caller owns the stateless transaction and must await commit before returning success. */
public interface IPaymentService<J extends IPaymentService<J>> {
    Uni<Attempt> prepare(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, Deposit deposit);
    Uni<Attempt> get(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, UUID id);
    Uni<Attempt> bind(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, UUID id, String reference);
    Uni<Receipt> confirm(Mutiny.StatelessSession session, ISystems<?, ?> system, WalletIdentity identity, Route route, Confirmation confirmation);
}
