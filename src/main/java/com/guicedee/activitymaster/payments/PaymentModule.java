package com.guicedee.activitymaster.payments;

import com.google.inject.*;
import com.guicedee.client.services.lifecycle.IGuiceModule;

public final class PaymentModule extends AbstractModule implements IGuiceModule<PaymentModule> {
    @Override protected void configure() {
        Key<IPaymentService<?>> generic = Key.get(new TypeLiteral<IPaymentService<?>>() {});
        Key<IPaymentService<PaymentService>> concrete = Key.get(new TypeLiteral<IPaymentService<PaymentService>>() {});
        bind(generic).to(concrete); bind(concrete).to(PaymentService.class); bind(IPaymentService.class).to(generic);
    }
}
