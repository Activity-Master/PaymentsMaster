package com.guicedee.activitymaster.payments;

import com.guicedee.client.services.config.IGuiceScanModuleInclusions;
import java.util.Set;

public final class PaymentInclusionModule implements IGuiceScanModuleInclusions<PaymentInclusionModule> {
    public Set<String> includeModules() { return Set.of("com.guicedee.activitymaster.payments"); }
}
