module com.guicedee.activitymaster.payments {
    requires transitive com.guicedee.activitymaster.wallet;
    exports com.guicedee.activitymaster.payments;
    opens com.guicedee.activitymaster.payments to com.google.guice, tools.jackson.databind;
    provides com.guicedee.client.services.lifecycle.IGuiceModule with com.guicedee.activitymaster.payments.PaymentModule;
    provides com.guicedee.client.services.config.IGuiceScanModuleInclusions with com.guicedee.activitymaster.payments.PaymentInclusionModule;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.IMasterSystem with com.guicedee.activitymaster.payments.PaymentSystem;
    provides com.guicedee.activitymaster.fsdm.client.services.systems.ISystemUpdate with com.guicedee.activitymaster.payments.PaymentSystemInstall;
}
