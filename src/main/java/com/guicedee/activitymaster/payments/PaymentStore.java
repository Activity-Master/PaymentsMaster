package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.fsdm.client.services.builders.warehouse.systems.ISystems;
import com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts;
import com.guicedee.activitymaster.fsdm.db.abstraction.WarehouseSCDTable;
import com.guicedee.activitymaster.fsdm.db.entities.events.*;
import com.guicedee.activitymaster.fsdm.db.entities.arrangement.Arrangement;
import com.guicedee.activitymaster.fsdm.db.entities.involvedparty.InvolvedParty;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Context;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Realm;
import com.guicedee.activitymaster.wallet.WalletIdentity;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.guicedee.activitymaster.payments.PaymentModels.*;
import static com.guicedee.activitymaster.fsdm.client.services.classifications.EnterpriseClassificationDataConcepts.*;

/** Payment attempts are secured FSDM Events. No payment-owned schema or tables. */
final class PaymentStore {
    static final Map<EnterpriseClassificationDataConcepts,List<String>> TAXONOMY=Map.of(
        EventXClassification,List.of("PaymentOperationKey","PaymentRealm","PaymentContextOwner","PaymentWalletProvider",
            "PaymentProvider","PaymentGateway","PaymentMerchantAccount","PaymentAmount","PaymentUnit","PaymentProviderReference","PaymentReferenceKey"),
        EventXEventType,List.of("PaymentAttemptType"),
        EventXInvolvedParty,List.of("PaymentActor","PaymentMerchant","PaymentProcessor"),
        EventXArrangement,List.of("PaymentClearing","PaymentDestination"), EventXEvent,List.of("PaymentSettlement"));

    Uni<Attempt> create(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, Attempt attempt) {
        return PaymentRows.lock(session,attempt.id())
            .chain(() -> session.createNativeQuery("select eventid from event.event where eventid=:id",UUID.class).setParameter("id",attempt.id()).getResultList())
            .chain(existing -> {
                if(!existing.isEmpty()) return load(session,system,attempt.id(),true).invoke(saved -> {
                    if(!saved.actor().equals(attempt.actor()) || !saved.route().equals(attempt.route()) || !saved.deposit().equals(attempt.deposit()))
                        throw new IllegalStateException("Payment operation key conflict");
                }).call(saved -> authorize(session,system,identity,saved,true));
                return PaymentRows.persist(session,new Event().setId(attempt.id()),system,identity)
                    .chain(event -> type(session,system,identity,event))
                    .chain(() -> party(session,system,identity,attempt.id(),"PaymentActor",attempt.actor().partyId()))
                    .chain(() -> party(session,system,identity,attempt.id(),"PaymentMerchant",attempt.route().merchantPartyId()))
                    .chain(() -> party(session,system,identity,attempt.id(),"PaymentProcessor",attempt.route().providerPartyId()))
                    .chain(() -> arrangement(session,system,identity,attempt.id(),"PaymentClearing",attempt.route().clearingId()))
                    .chain(() -> arrangement(session,system,identity,attempt.id(),"PaymentDestination",attempt.deposit().walletId()))
                    .chain(() -> {
                        Map<String,String> values=new LinkedHashMap<>();
                        values.put("PaymentOperationKey",attempt.deposit().operationKey().toString());
                        values.put("PaymentRealm",attempt.actor().context().realm().name());
                        values.put("PaymentContextOwner",attempt.actor().context().ownerId().toString());
                        values.put("PaymentWalletProvider",attempt.actor().walletProviderId());
                        values.put("PaymentProvider",attempt.route().paymentProviderId());
                        values.put("PaymentGateway",attempt.route().gateway());
                        values.put("PaymentMerchantAccount",attempt.route().merchant());
                        values.put("PaymentAmount",attempt.deposit().amount()); values.put("PaymentUnit",attempt.deposit().unit());
                        Uni<Void> writes=Uni.createFrom().voidItem();
                        for(var value:values.entrySet()) writes=writes.chain(() -> classify(session,system,identity,attempt.id(),value.getKey(),value.getValue()));
                        return writes;
                    }).replaceWith(attempt);
            });
    }

    /** Internal attribution only. Public operations additionally authorize every Event and relationship. */
    Uni<Attempt> load(Mutiny.StatelessSession session, ISystems<?,?> system, UUID id, boolean lock) {
        Uni<Void> locked=lock ? PaymentRows.lock(session,id) : Uni.createFrom().voidItem();
        return locked.chain(() -> session.createNativeQuery("""
            select e.eventid from event.event e join dbo.activeflag f on f.activeflagid=e.activeflagid
            where e.eventid=:id and e.enterpriseid=:enterprise and e.systemid=:system and f.allowaccess=1
            and e.effectivefromdate<=statement_timestamp() and e.effectivetodate>statement_timestamp() for share of e,f
            """,UUID.class).setParameter("id",id).setParameter("enterprise",system.getEnterprise().getId()).setParameter("system",system.getId()).getResultList())
            .chain(rows -> PaymentRows.require(rows.size()==1))
            .chain(() -> links(session,system,id,"eventxeventtype","eventtypeid","EventXEventType"))
            .chain(rows -> session.createNativeQuery("""
                select eventtypeid from event.eventtype where eventtypeid=:type and eventtypename='Payment Attempt'
                and enterpriseid=:enterprise and systemid=:system
                """,UUID.class).setParameter("type",requiredId(rows,"PaymentAttemptType"))
                .setParameter("enterprise",system.getEnterprise().getId()).setParameter("system",system.getId()).getResultList())
            .chain(rows -> PaymentRows.require(rows.size()==1))
            .chain(() -> links(session,system,id,"eventxclassification","value","EventXClassification"))
            .chain(values -> links(session,system,id,"eventxinvolvedparty","involvedpartyid","EventXInvolvedParty")
                .chain(parties -> links(session,system,id,"eventxarrangement","arrangementid","EventXArrangement")
                    .chain(arrangements -> links(session,system,id,"eventxevent","childeventid","EventXEvent")
                        .map(events -> new Attempt(id,new Actor(requiredId(parties,"PaymentActor"),system.getEnterprise().getId(),
                            new Context(Realm.valueOf(required(values,"PaymentRealm")),UUID.fromString(required(values,"PaymentContextOwner"))),required(values,"PaymentWalletProvider")),
                            new Route(required(values,"PaymentGateway"),required(values,"PaymentMerchantAccount"),requiredId(arrangements,"PaymentClearing"),
                                required(values,"PaymentProvider"),requiredId(parties,"PaymentMerchant"),requiredId(parties,"PaymentProcessor")),
                            new Deposit(UUID.fromString(required(values,"PaymentOperationKey")),requiredId(arrangements,"PaymentDestination"),
                                required(values,"PaymentAmount"),required(values,"PaymentUnit")),optional(values,"PaymentProviderReference"),optionalId(events,"PaymentSettlement"))))));
    }

    Uni<Void> authorize(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, Attempt attempt, boolean write) {
        Event event=new Event().setId(attempt.id());
        return (write ? event.canWrite(session,system,identity.tokens()) : event.canRead(session,system,identity.tokens())).chain(PaymentRows::require)
            .chain(() -> readableLinks(session,system,identity,attempt.id(),"eventxclassification",EventXClassification::new))
            .chain(() -> readableLinks(session,system,identity,attempt.id(),"eventxeventtype",EventXEventType::new))
            .chain(() -> readableLinks(session,system,identity,attempt.id(),"eventxinvolvedparty",EventXInvolvedParty::new))
            .chain(() -> readableLinks(session,system,identity,attempt.id(),"eventxarrangement",EventXArrangement::new))
            .chain(() -> readableLinks(session,system,identity,attempt.id(),"eventxevent",EventXEvent::new));
    }

    Uni<Attempt> bind(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, Attempt attempt, String reference) {
        if(attempt.reference()!=null && !attempt.reference().equals(reference)) return Uni.createFrom().failure(new IllegalStateException("Provider payment reference conflict"));
        String gateway=attempt.route().gateway(),merchant=attempt.route().merchant();
        UUID key=UUID.nameUUIDFromBytes((gateway.length()+":"+gateway+merchant.length()+":"+merchant+reference.length()+":"+reference).getBytes(StandardCharsets.UTF_8));
        // Claim under a shared merchant/reference lock, including across enterprise boundaries. Historical claims remain reserved.
        return PaymentRows.lock(session,key).chain(() -> session.createNativeQuery("""
            select x.eventid from event.eventxclassification x
            join classification.classification c on c.classificationid=x.classificationid
            join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid
            join dbo.systems s on s.systemid=x.systemid
            where c.classificationname='PaymentReferenceKey' and d.classificationdataconceptname='EventXClassification'
            and s.systemname=:system and x.value=:key for share of x
            """,UUID.class).setParameter("system",PaymentSystem.NAME).setParameter("key",key.toString()).getResultList())
            .invoke(ids -> { if(ids.stream().anyMatch(id -> !id.equals(attempt.id()))) throw new IllegalStateException("Provider payment already claimed"); })
            .chain(() -> attempt.reference()!=null ? Uni.createFrom().voidItem() : classify(session,system,identity,attempt.id(),"PaymentReferenceKey",key.toString())
                .chain(() -> classify(session,system,identity,attempt.id(),"PaymentProviderReference",reference)))
            .replaceWith(new Attempt(attempt.id(),attempt.actor(),attempt.route(),attempt.deposit(),reference,attempt.eventId()));
    }
    Uni<Void> settle(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, Attempt attempt, UUID eventId) {
        if(attempt.eventId()!=null) return attempt.eventId().equals(eventId) ? Uni.createFrom().voidItem() : Uni.createFrom().failure(new IllegalStateException("Settlement conflict"));
        return PaymentRows.role(session,system,"PaymentSettlement","EventXEvent").chain(role -> {
            EventXEvent link=new EventXEvent().setParentEventID(new Event().setId(attempt.id())).setChildEventID(new Event().setId(eventId));
            link.setId(UUID.randomUUID()); link.setClassificationID(role); link.setValue("1");
            return PaymentRows.persist(session,link,system,identity).replaceWithVoid();
        });
    }
    private Uni<Void> type(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, Event event) {
        return session.createNativeQuery("select eventtypeid from event.eventtype where eventtypename='Payment Attempt' and enterpriseid=:enterprise and systemid=:system",UUID.class)
            .setParameter("enterprise",system.getEnterprise().getId()).setParameter("system",system.getId()).getSingleResult()
            .chain(type -> PaymentRows.role(session,system,"PaymentAttemptType","EventXEventType").chain(role -> {
                EventXEventType link=new EventXEventType().setEventID(event).setEventTypeID(new EventType().setId(type));
                link.setId(UUID.randomUUID()); link.setClassificationID(role); link.setValue("1");
                return PaymentRows.persist(session,link,system,identity).replaceWithVoid();
            }));
    }
    private Uni<Void> classify(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, UUID event, String name, String value) {
        return PaymentRows.role(session,system,name,"EventXClassification").chain(role -> {
            EventXClassification link=new EventXClassification().setEventID(new Event().setId(event));
            link.setId(UUID.randomUUID()); link.setClassificationID(role); link.setValue(value);
            return PaymentRows.persist(session,link,system,identity).replaceWithVoid();
        });
    }
    private Uni<Void> party(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, UUID event, String name, UUID party) {
        return session.createNativeQuery("""
            select p.involvedpartyid from party.involvedparty p join dbo.activeflag f on f.activeflagid=p.activeflagid
            where p.involvedpartyid=:id and p.enterpriseid=:enterprise and f.allowaccess=1
            and p.effectivefromdate<=statement_timestamp() and p.effectivetodate>statement_timestamp() for share of p,f
            """,UUID.class).setParameter("id",party).setParameter("enterprise",identity.enterpriseId()).getResultList()
            .chain(rows -> PaymentRows.require(rows.size()==1))
            .chain(() -> new InvolvedParty().setId(party).canRead(session,system,identity.tokens())).chain(PaymentRows::require)
            .chain(() -> PaymentRows.role(session,system,name,"EventXInvolvedParty")).chain(role -> {
                EventXInvolvedParty link=new EventXInvolvedParty().setEventID(new Event().setId(event)).setInvolvedPartyID(new InvolvedParty().setId(party));
                link.setId(UUID.randomUUID()); link.setClassificationID(role); link.setValue("1");
                return PaymentRows.persist(session,link,system,identity).replaceWithVoid();
            });
    }
    private Uni<Void> arrangement(Mutiny.StatelessSession session, ISystems<?,?> system, WalletIdentity identity, UUID event, String name, UUID arrangement) {
        return new Arrangement().setId(arrangement).canRead(session,system,identity.tokens()).chain(PaymentRows::require)
            .chain(() -> PaymentRows.role(session,system,name,"EventXArrangement")).chain(role -> {
                EventXArrangement link=new EventXArrangement().setEventID(new Event().setId(event)).setArrangementID(new Arrangement().setId(arrangement));
                link.setId(UUID.randomUUID()); link.setClassificationID(role); link.setValue("1");
                return PaymentRows.persist(session,link,system,identity).replaceWithVoid();
            });
    }
    private Uni<List<Object[]>> links(Mutiny.StatelessSession session, ISystems<?,?> system, UUID event, String table, String value, String concept) {
        return session.createNativeQuery("select c.classificationname,x."+value+",case when f.allowaccess=1 and cf.allowaccess=1 "
            +"and x.effectivefromdate<=statement_timestamp() and x.effectivetodate>statement_timestamp() "
            +"and c.effectivefromdate<=statement_timestamp() and c.effectivetodate>statement_timestamp() then 1 else 0 end from event."+table+" x "
            +"join classification.classification c on c.classificationid=x.classificationid "
            +"join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid "
            +"join dbo.activeflag f on f.activeflagid=x.activeflagid "
            +"join dbo.activeflag cf on cf.activeflagid=c.activeflagid "
            +"where x."+eventColumn(table)+"=:event and x.enterpriseid=:enterprise and x.systemid=:system "
            +"and c.enterpriseid=:enterprise and c.systemid=:system and d.classificationdataconceptname=:concept "
            +"for share of x,c,f,cf",Object[].class)
            .setParameter("event",event).setParameter("enterprise",system.getEnterprise().getId()).setParameter("system",system.getId())
            .setParameter("concept",concept).getResultList().invoke(rows -> {
                // Never reinterpret a disabled reference/settlement as a new, unbound payment.
                if(rows.stream().anyMatch(row -> ((Number)row[2]).intValue()!=1)) throw new SecurityException("Inactive payment relationship or taxonomy");
            });
    }
    private <R extends WarehouseSCDTable<R,?,UUID,?>> Uni<Void> readableLinks(Mutiny.StatelessSession session, ISystems<?,?> system,
            WalletIdentity identity, UUID event, String table, java.util.function.Supplier<R> factory) {
        String entity=factory.get().getClass().getSimpleName();
        String parent=table.equals("eventxevent") ? "parentEventID" : "eventID";
        return session.createQuery("select x.id from "+entity+" x where x."+parent
            +".id=:event and x.enterpriseID.id=:enterprise and x.systemID.id=:system",UUID.class)
            .setParameter("event",event).setParameter("enterprise",identity.enterpriseId()).setParameter("system",system.getId()).getResultList()
            .chain(ids -> {
                Uni<Void> chain=Uni.createFrom().voidItem();
                for(UUID id:ids) chain=chain.chain(() -> factory.get().setId(id).canRead(session,system,identity.tokens()).chain(PaymentRows::require));
                return chain;
            });
    }
    private static String eventColumn(String table) { return table.equals("eventxevent") ? "parenteventid" : "eventid"; }
    private static String optional(List<Object[]> rows,String key) {
        var values=rows.stream().filter(row -> key.equals(row[0])).toList();
        if(values.size()>1) throw new IllegalStateException("Ambiguous payment classification: "+key);
        return values.isEmpty() ? null : values.getFirst()[1].toString();
    }
    private static String required(List<Object[]> rows,String key) {
        String value=optional(rows,key); if(value==null) throw new IllegalStateException("Missing payment classification: "+key); return value;
    }
    private static UUID requiredId(List<Object[]> rows,String key) { return UUID.fromString(required(rows,key)); }
    private static UUID optionalId(List<Object[]> rows,String key) { String value=optional(rows,key); return value==null ? null : UUID.fromString(value); }
}
