package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.administration.MasterDefaultSystem;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.enterprise.IEnterprise;
import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.db.entities.systems.Systems;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;

public final class PaymentSystem extends MasterDefaultSystem<PaymentSystem> {
    public static final String NAME = "Payment Master";
    @Override public String getSystemName() { return NAME; }
    @Override public String getSystemDescription() { return "Provider payment orchestration backed by Wallet Master"; }
    @Override public int totalTasks() { return 0; }
    @Override public Uni<Void> createDefaults(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) { return Uni.createFrom().voidItem(); }
    @Override public Uni<ISystems<?, ?>> getSystem(Mutiny.StatelessSession session, IEnterprise<?, ?> enterprise) {
        return session.createNativeQuery("""
            select s.systemid, s.systemdesc, s.systemhistoryname from dbo.systems s
            join dbo.activeflag f on f.activeflagid=s.activeflagid
            where s.enterpriseid=:enterprise and s.systemname=:name and f.allowaccess=1
              and s.effectivefromdate<=statement_timestamp() and s.effectivetodate>statement_timestamp()
            order by exists (select 1 from event.eventtype t where t.systemid=s.systemid
                and t.enterpriseid=s.enterpriseid and t.eventtypename='Payment Attempt') desc,
                s.warehousecreatedtimestamp, s.systemid limit 1
            """, Object[].class).setParameter("enterprise",enterprise.getId()).setParameter("name",NAME)
            .getResultList().chain(rows -> {
                if(rows.size()!=1) return Uni.createFrom().failure(new SecurityException("Payment system unavailable"));
                Object[] row=rows.getFirst();
                Systems system=new Systems((java.util.UUID)row[0],NAME,(String)row[1],(String)row[2]);
                system.setEnterpriseID(enterprise);
                return Uni.createFrom().item(system);
            });
    }
}
