package com.guicedee.activitymaster.payments.test;

import com.guicedee.activitymaster.payments.*;
import com.guicedee.activitymaster.ScopedFsdmFixture;
import com.guicedee.activitymaster.wallet.*;
import com.guicedee.activitymaster.fsdm.client.services.*;
import com.guicedee.activitymaster.fsdm.client.services.administration.ActivityMasterConfiguration;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Context;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Realm;
import com.guicedee.client.IGuiceContext;
import com.google.inject.Key;
import com.google.inject.name.Names;
import io.smallrye.mutiny.Uni;
import org.hibernate.reactive.mutiny.Mutiny;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;
import java.time.Duration;
import java.util.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static com.guicedee.activitymaster.wallet.WalletModels.*;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

/** Production EntityAssist payment/wallet operations on disposable canonical PostgreSQL. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentIntegrationTest {
    private static final String ENTERPRISE="TestEnterprise";
    private Mutiny.SessionFactory factory;
    private WalletApi wallet;
    private PaymentApi payments;
    private WalletIdentity identity;
    private volatile WalletIdentity current;
    private UUID systemId,paymentSystemId,clearing;
    private Route route;
    private PaymentHost host;
    private SignedGateway gateway;
    private PostgreSQLContainer<?> postgres;
    private ScopedFsdmFixture scoped;
    @BeforeAll void boot() throws Exception {
        Class<?> database=Class.forName("com.guicedee.activitymaster.PostgreSQLTestDBModule");
        var getContainer=database.getMethod("getPostgresContainer"); getContainer.setAccessible(true);
        postgres=(PostgreSQLContainer<?>)getContainer.invoke(null);
        // The shared legacy fixture omits PK constraints; add only the keys referenced by the managed migrations.
        for (String pair:List.of("dbo.enterprise:enterpriseid","dbo.systems:systemid","dbo.activeflag:activeflagid",
                "party.involvedparty:involvedpartyid","party.involvedpartyorganic:involvedpartyorganicid",
                "event.event:eventid","arrangement.arrangement:arrangementid","classification.classification:classificationid",
                "security.securitytoken:securitytokenid","resource.resourceitem:resourceitemid","product.product:productid",
                "address.address:addressid","geography.geography:geographyid","rules.rules:rulesid")) {
            String[] parts=pair.split(":");
            sql("CREATE UNIQUE INDEX wallet_test_"+parts[0].replace('.','_')+" ON "+parts[0]+"("+parts[1]+")");
        }
        for (String name:List.of("transactions.sql")) {
            try (var module=ModuleLayer.boot().configuration().findModule("com.guicedee.activitymaster.fsdm").orElseThrow().reference().open();
                 var script=module.open("db/"+name).orElseThrow()) {
                postgres.copyFileToContainer(Transferable.of(script.readAllBytes()),"/tmp/"+name);
            }
            var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-f","/tmp/"+name);
            assertEquals(0,result.getExitCode(),result.getStderr());
        }
        ActivityMasterConfiguration.get().setApplicationEnterpriseName(ENTERPRISE);
        IGuiceContext.instance();
        factory=IGuiceContext.get(Key.get(Mutiny.SessionFactory.class,Names.named("ActivityMaster-Test")));
        IEnterpriseService<?> enterprises=IGuiceContext.get(IEnterpriseService.class);
        await(factory.withStatelessTransaction(session -> enterprises.getEnterprise(session,ENTERPRISE)
                .onFailure().recoverWithUni(failure -> {
                    var enterprise=enterprises.get(); enterprise.setName(ENTERPRISE); enterprise.setDescription("Wallet integration fixture");
                    return enterprises.createNewEnterprise(session,enterprise);
                }).replaceWithVoid()));
        await(factory.withStatelessSession(session -> enterprises.startNewEnterprise(session,ENTERPRISE,"admin","adminadmin!@")));
        sql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements");
        sql("SELECT pg_stat_statements_reset()");
        await(factory.withStatelessTransaction(session -> enterprises.getEnterprise(session,ENTERPRISE)
                .chain(enterprise -> enterprises.loadUpdates(session,enterprise))));
        identity=await(SessionUtils.withActivityMaster(ENTERPRISE,WalletSystem.NAME,tuple -> {
            systemId=tuple.getItem3().getId();
            return tuple.getItem1().createNativeQuery("select securitytoken from security.securitytoken where securitytokenfriendlyname='admin' and enterpriseid=:enterprise",String.class)
                .setParameter("enterprise",tuple.getItem2().getId()).getSingleResult().chain(credential -> {
            UUID actorCredential=UUID.fromString(credential);
            return tuple.getItem1().createNativeQuery("select involvedpartyid from party.involvedparty where enterpriseid=:enterprise limit 1",UUID.class)
                    .setParameter("enterprise",tuple.getItem2().getId()).getSingleResult()
                    .map(party -> new WalletIdentity(party,tuple.getItem2().getId(),new Context(Realm.WORK,tuple.getItem2().getId()),actorCredential));
            });
        }));
        provisionGrants();
        IWalletService<?> service=IGuiceContext.get(IWalletService.class);
        wallet=new WalletApi(() -> Uni.createFrom().item(identity),service);
        // Host provisioning of a clearing account uses the same canonical Arrangement. Wallet API never creates one implicitly.
        clearing=await(wallet.create(ENTERPRISE,new Create(UUID.randomUUID()))).arrangementId();
        sql("UPDATE arrangement.arrangementxarrangementtype SET arrangementtypeid=(SELECT arrangementtypeid FROM arrangement.arrangementtype WHERE arrangementtypename='Wallet Clearing' AND enterpriseid='"+identity.enterpriseId()+"') WHERE arrangementid='"+clearing+"'");
        paymentSystemId=await(SessionUtils.withActivityMaster(ENTERPRISE,PaymentSystem.NAME,t ->
            IGuiceContext.get(PaymentSystem.class).getSystem(t.getItem1(),t.getItem2()).map(s -> s.getId())));
        await(SessionUtils.withActivityMaster(ENTERPRISE,PaymentSystem.NAME,t -> new PaymentSystemInstall().update(t.getItem1(),t.getItem2())));
        Long scopedType=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from event.eventtype where eventtypename=:name and systemid=:system",Long.class)
            .setParameter("name",com.guicedee.activitymaster.fsdm.transactions.FsdmBehaviorAuthority.type(paymentSystemId,"Scoped Provider Installation"))
            .setParameter("system",paymentSystemId).getSingleResult()));
        assertEquals(Long.valueOf(1),scopedType);
        grantProvider("payments",paymentSystemId,List.of("payment.create","payment.read","payment.confirm"));
        Long installed=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from event.event where eventid=:id", Long.class)
            .setParameter("id", ScopedFsdmFixture.installationId(paymentSystemId,"WORK",identity.enterpriseId(),"payments"))
            .getSingleResult()));
        assertEquals(Long.valueOf(1), installed);
        UUID installation=ScopedFsdmFixture.installationId(paymentSystemId,"WORK",identity.enterpriseId(),"payments");
        Long typed=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from event.eventxeventtype where eventid=:id", Long.class).setParameter("id",installation).getSingleResult()));
        Long classified=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from event.eventxclassification where eventid=:id", Long.class).setParameter("id",installation).getSingleResult()));
        assertEquals(Long.valueOf(1),typed);
        assertEquals(Long.valueOf(3),classified);
        route=new Route("fixture","merchant",clearing,"payments",identity.partyId(),identity.partyId());
        current=identity;
        gateway=new SignedGateway();
        host=new PaymentHost() {
            public Uni<Route> route(WalletIdentity actor,Deposit request) { return Uni.createFrom().item(route); }
            public Uni<WalletIdentity> currentActor(Attempt attempt) { return Uni.createFrom().item(current); }
        };
        payments=new PaymentApi(() -> Uni.createFrom().item(current),host,id -> {
            if(!id.equals("fixture")) throw new SecurityException("Unknown gateway");
            return gateway;
        },IGuiceContext.get(IPaymentService.class),new PaymentTransactions());
        writeQueryStats("payments-setup-queries.csv");
        sql("SELECT pg_stat_statements_reset()");
    }
    @AfterAll void writeQueryPerformance() throws Exception {
        writeQueryStats("payments-postgres-queries.csv");
        Path directory=Path.of("target","performance");
        Files.createDirectories(directory);
        String[] plans={
            "select coalesce(sum(signed_amount),0) from transactions.entry where arrangement_id='"+clearing+"' and unit='POINTS'",
            "select entry_id from transactions.entry where arrangement_id='"+clearing+"' and unit='POINTS' order by warehousecreatedtimestamp desc,entry_id limit 50",
            "select x.eventid from event.eventxclassification x join classification.classification c on c.classificationid=x.classificationid join classification.classificationdataconcept d on d.classificationdataconceptid=c.classificationdataconceptid join dbo.systems s on s.systemid=x.systemid where c.classificationname='PaymentReferenceKey' and d.classificationdataconceptname='EventXClassification' and s.systemname='Payment Master' and x.value='missing-reference'",
            "select childeventid from event.eventxevent where parenteventid='"+UUID.randomUUID()+"'"
        };
        StringBuilder report=new StringBuilder("PostgreSQL EXPLAIN (ANALYZE, BUFFERS) on the integration fixture\n");
        for(String statement:plans) {
            var plan=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),
                "-d",postgres.getDatabaseName(),"-c","EXPLAIN (ANALYZE, BUFFERS) "+statement);
            assertEquals(0,plan.getExitCode(),plan.getStderr());
            report.append("\n").append(statement).append("\n").append(plan.getStdout());
        }
        String historyScale = "CREATE TEMP TABLE perf_entry AS SELECT e.entry_id,e.arrangement_id,e.unit,e.event_id,"
            + "e.warehousecreatedtimestamp + g * interval '1 microsecond' AS warehousecreatedtimestamp "
            + "FROM transactions.entry e CROSS JOIN generate_series(1,1000) g WHERE e.arrangement_id='"+clearing+"'; "
            + "CREATE INDEX perf_entry_arrangement_unit ON perf_entry(arrangement_id,unit,event_id); ANALYZE perf_entry; "
            + "EXPLAIN (ANALYZE, BUFFERS) SELECT entry_id FROM perf_entry WHERE arrangement_id='"+clearing
            + "' AND unit='POINTS' ORDER BY warehousecreatedtimestamp DESC,entry_id LIMIT 50; "
            + "CREATE INDEX perf_entry_history ON perf_entry(arrangement_id,unit,warehousecreatedtimestamp DESC,entry_id); "
            + "ANALYZE perf_entry; EXPLAIN (ANALYZE, BUFFERS) SELECT entry_id FROM perf_entry WHERE arrangement_id='"
            +clearing+"' AND unit='POINTS' ORDER BY warehousecreatedtimestamp DESC,entry_id LIMIT 50";
        var scale=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),
            "-d",postgres.getDatabaseName(),"-c",historyScale);
        assertEquals(0,scale.getExitCode(),scale.getStderr());
        report.append("\nSynthetic history index comparison (fixture rows multiplied 1000x)\n")
            .append(scale.getStdout());
        Files.writeString(directory.resolve("payments-postgres-plans.txt"),report.toString());
    }
    private void writeQueryStats(String filename) throws Exception {
        String query = "COPY (SELECT calls, round(total_exec_time::numeric,3) AS total_ms, "
            + "round(mean_exec_time::numeric,3) AS mean_ms, round(max_exec_time::numeric,3) AS max_ms, "
            + "rows, shared_blks_hit, shared_blks_read, "
            + "regexp_replace(query, E'[\\n\\r\\t]+', ' ', 'g') AS sql "
            + "FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database()) "
            + "AND query ~* '(transactions[.]|event[.]|arrangement[.]|classification[.]|security[.]|party[.])' "
            + "ORDER BY total_exec_time DESC) TO STDOUT WITH CSV HEADER";
        var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),
            "-d",postgres.getDatabaseName(),"-c",query);
        assertEquals(0,result.getExitCode(),result.getStderr());
        assertTrue(result.getStdout().contains("shared_blks_read,sql"),"Query statistics were not collected");
        Path directory=Path.of("target","performance");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(filename),result.getStdout());
    }
    private void provisionGrants() throws Exception {
        scoped=new ScopedFsdmFixture(this::sql,identity.enterpriseId(),identity.identityToken());
        scoped.install(systemId,"WORK",identity.enterpriseId(),"wallet");
        for(String action:List.of("create","read","post","transfer","deposit","withdrawal")) {
            scoped.grant(systemId,"WORK",identity.enterpriseId(),"wallet",identity.partyId(),"wallet."+action);
        }
    }

    @Test void paymentDepositTransferWithdrawalAndDuplicateConfirmation() {
        UUID a=account(),b=account(); Deposit deposit=deposit(a,"100.00");
        Started started=await(payments.start(ENTERPRISE,deposit));
        assertEquals("PENDING",started.status()); assertEquals("0.00000000",balance(a));
        Receipt receipt=await(confirm(deposit));
        assertEquals(receipt,await(confirm(deposit)));
        Attempt saved=await(payments.get(ENTERPRISE,deposit.operationKey()));
        assertEquals("SETTLED",saved.status()); assertEquals(receipt.eventId(),saved.eventId());
        assertEquals(started.paymentId(),saved.id());
        await(wallet.move(ENTERPRISE,Action.TRANSFER,movement(a,b,"12.50")));
        await(wallet.move(ENTERPRISE,Action.WITHDRAWAL,movement(b,clearing,"2.50")));
        assertEquals("87.50000000",balance(a)); assertEquals("10.00000000",balance(b));
        assertThrows(IllegalStateException.class,() -> await(payments.start(ENTERPRISE,new Deposit(deposit.operationKey(),a,"101","POINTS"))));
        assertEquals("SETTLED",await(payments.start(ENTERPRISE,deposit)).status());
    }

    @Test void missingIdentityWrongEnterpriseContextAndCredentialAreDenied() {
        Deposit deposit=deposit(account(),"5"); await(payments.start(ENTERPRISE,deposit));
        try {
            current=null;
            assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit)));
            assertThrows(SecurityException.class,() -> await(confirm(deposit)));
            current=new WalletIdentity(identity.partyId(),UUID.randomUUID(),new Context(Realm.PERSONAL,identity.partyId()),identity.identityToken());
            assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit)));
            current=new WalletIdentity(identity.partyId(),identity.enterpriseId(),new Context(Realm.SOCIAL,identity.partyId()),identity.identityToken());
            assertThrows(SecurityException.class,() -> await(confirm(deposit)));
            current=new WalletIdentity(identity.partyId(),identity.enterpriseId(),identity.context(),UUID.randomUUID());
            assertThrows(SecurityException.class,() -> await(confirm(deposit)));
        } finally { current=identity; }
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void signaturesMerchantAmountsAndCurrencyMustMatch() {
        Deposit deposit=deposit(account(),"7"); await(payments.start(ENTERPRISE,deposit));
        Callback valid=callback(deposit,"7","POINTS",reference(deposit));
        Callback forged=new Callback(valid.body(),Map.of("signature",List.of("00")));
        assertThrows(SecurityException.class,() -> await(payments.confirm(ENTERPRISE,"fixture","merchant",forged)));
        assertThrows(SecurityException.class,() -> await(payments.confirm(ENTERPRISE,"fixture","merchant",callback(deposit,"8","POINTS",reference(deposit)))));
        assertThrows(SecurityException.class,() -> await(payments.confirm(ENTERPRISE,"fixture","merchant",callback(deposit,"7","USD",reference(deposit)))));
        assertThrows(SecurityException.class,() -> await(payments.confirm(ENTERPRISE,"fixture","other",valid)));
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void securityTokenRowIdCannotSubstituteForIdentifyingCredential() {
        Deposit deposit=deposit(account(),"2"); await(payments.start(ENTERPRISE,deposit));
        UUID rowId=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select securitytokenid from security.securitytoken where securitytoken=:credential",UUID.class)
            .setParameter("credential",identity.identityToken().toString()).getSingleResult()));
        assertNotEquals(identity.identityToken(),rowId);
        current=new WalletIdentity(identity.partyId(),identity.enterpriseId(),identity.context(),rowId);
        try { assertThrows(SecurityException.class,() -> await(confirm(deposit))); }
        finally { current=identity; }
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void deniedPostingGrantNeverStartsExternalCheckout() throws Exception {
        Deposit deposit=deposit(account(),"2"); int before=gateway.starts;
        UUID grant=ScopedFsdmFixture.grantId(systemId,"WORK",identity.enterpriseId(),"wallet",identity.partyId(),"wallet.post");
        scoped.disable(grant);
        try { assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { scoped.enable(grant); }
        assertEquals(before,gateway.starts);
        assertThrows(SecurityException.class,() -> await(payments.get(ENTERPRISE,deposit.operationKey())));
    }

    @Test void scopedGrantNeedsActorRowPermissionAndCurrentMembership() throws Exception {
        UUID grant=ScopedFsdmFixture.grantId(paymentSystemId,"WORK",identity.enterpriseId(),"payments",
            identity.partyId(),"payment.create");
        Deposit deposit=deposit(account(),"2");
        sql("update event.eventsecuritytoken set readallowed=0 where eventsid='"+grant+"'");
        try { assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { sql("update event.eventsecuritytoken set readallowed=1 where eventsid='"+grant+"'"); }
        sql("update event.eventxinvolvedparty set effectivetodate=statement_timestamp()-interval '1 second' where eventid='"+grant+"'");
        try { assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { sql("update event.eventxinvolvedparty set effectivetodate='9999-12-31' where eventid='"+grant+"'"); }
    }

    @Test void changedHostRouteCannotReinterpretExistingPayment() {
        Deposit deposit=deposit(account(),"2"); await(payments.start(ENTERPRISE,deposit));
        Route original=route;
        route=new Route(original.gateway(),"other-merchant",original.clearingId(),original.paymentProviderId(),original.merchantPartyId(),original.providerPartyId());
        try { assertThrows(SecurityException.class,() -> await(confirm(deposit))); }
        finally { route=original; }
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void ordinaryWalletCannotBeUsedAsClearingEvenByHostMisconfiguration() {
        Deposit deposit=deposit(account(),"2"); Route original=route; int before=gateway.starts;
        route=new Route(original.gateway(),original.merchant(),account(),original.paymentProviderId(),original.merchantPartyId(),original.providerPartyId());
        try { assertThrows(SecurityException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { route=original; }
        assertEquals(before,gateway.starts);
    }

    @Test void grantsStayProviderQualifiedAndRevocationAppliesToRetries() throws Exception {
        Deposit deposit=deposit(account(),"2"); await(payments.start(ENTERPRISE,deposit));
        grantProvider("other-payments",paymentSystemId,List.of("payment.confirm"));
        UUID own=ScopedFsdmFixture.grantId(paymentSystemId,"WORK",identity.enterpriseId(),"payments",identity.partyId(),"payment.confirm");
        scoped.disable(own);
        try {
            assertThrows(SecurityException.class,() -> await(confirm(deposit)));
            scoped.enable(own);
            await(confirm(deposit));
            scoped.disable(own);
            assertThrows(SecurityException.class,() -> await(confirm(deposit)));
        } finally { scoped.enable(own); }
        assertEquals("2.00000000",balance(deposit.walletId()));
    }

    @Test void installationIsIdempotentAndCreatesNoPaymentSchema() {
        await(SessionUtils.withActivityMaster(ENTERPRISE,PaymentSystem.NAME,t -> new PaymentSystemInstall().update(t.getItem1(),t.getItem2())));
        await(SessionUtils.withActivityMaster(ENTERPRISE,PaymentSystem.NAME,t -> new PaymentSystemInstall().update(t.getItem1(),t.getItem2())));
        long types=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from event.eventtype where eventtypename='Payment Attempt' and systemid=:system",Long.class)
            .setParameter("system",paymentSystemId).getSingleResult()));
        assertEquals(1,types);
        long schemas=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from pg_namespace where nspname='payments'",Long.class).getSingleResult()));
        assertEquals(0,schemas);
        long oldSpaces=await(factory.withStatelessTransaction(session -> session.createNativeQuery(
            "select count(*) from pg_namespace where nspname='space'",Long.class).getSingleResult()));
        assertEquals(0,oldSpaces);
    }

    @Test void currentPaymentAndWalletGrantsAreRequiredEvenForSettledRetries() throws Exception {
        Deposit deposit=deposit(account(),"4"); await(payments.start(ENTERPRISE,deposit)); await(confirm(deposit));
        for(String behavior:List.of("payment.confirm","wallet.post","wallet.deposit")) {
            UUID system=behavior.startsWith("payment.")?paymentSystemId:systemId;
            String provider=behavior.startsWith("payment.")?"payments":"wallet";
            UUID grant=ScopedFsdmFixture.grantId(system,"WORK",identity.enterpriseId(),provider,identity.partyId(),behavior);
            scoped.disable(grant);
            try { assertThrows(SecurityException.class,() -> await(confirm(deposit))); }
            finally { scoped.enable(grant); }
        }
        UUID installation=ScopedFsdmFixture.installationId(paymentSystemId,"WORK",identity.enterpriseId(),"payments");
        scoped.disable(installation);
        try { assertThrows(SecurityException.class,() -> await(confirm(deposit))); }
        finally { scoped.enable(installation); }
        assertEquals("4.00000000",balance(deposit.walletId()));
    }

    @Test void bothArrangementWriteGrantsAndPaymentEventAccessAreRequired() throws Exception {
        Deposit deposit=deposit(account(),"9"); Started started=await(payments.start(ENTERPRISE,deposit));
        for(UUID arrangement:List.of(clearing,deposit.walletId())) {
            sql("update arrangement.arrangementsecuritytoken set createallowed=0,updateallowed=0 where arrangementid='"+arrangement+"'");
            try { assertThrows(SecurityException.class,() -> await(confirm(deposit))); }
            finally { sql("update arrangement.arrangementsecuritytoken set createallowed=1,updateallowed=1 where arrangementid='"+arrangement+"'"); }
        }
        sql("update event.eventsecuritytoken set readallowed=0,createallowed=0,updateallowed=0 where eventsid='"+started.paymentId()+"'");
        assertThrows(SecurityException.class,() -> await(confirm(deposit)));
        assertThrows(SecurityException.class,() -> await(payments.get(ENTERPRISE,deposit.operationKey())));
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void classifiedRelationshipReadGrantsArePreserved() throws Exception {
        Deposit deposit=deposit(account(),"3"); Started started=await(payments.start(ENTERPRISE,deposit));
        sql("update event.eventxclassificationsecuritytoken set readallowed=0 where eventxclassificationsid in "
            +"(select eventxclassificationid from event.eventxclassification where eventid='"+started.paymentId()+"')");
        assertThrows(SecurityException.class,() -> await(confirm(deposit)));
        assertEquals("0.00000000",balance(deposit.walletId()));
    }

    @Test void callbackRecoversCheckoutUnknownOutcomeAndDoesNotNeedBrowserIdentity() {
        Deposit deposit=deposit(account(),"6"); gateway.failStart=true;
        try { assertThrows(IllegalStateException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { gateway.failStart=false; }
        assertNull(await(payments.get(ENTERPRISE,deposit.operationKey())).reference());
        PaymentApi callbackOnly=new PaymentApi(new WalletIdentityProvider.Deny(),host,id -> gateway,
            IGuiceContext.get(IPaymentService.class),new PaymentTransactions());
        await(callbackOnly.confirm(ENTERPRISE,"fixture","merchant",callback(deposit,deposit.amount(),deposit.unit(),reference(deposit))));
        assertEquals("6.00000000",balance(deposit.walletId()));
        assertEquals("SETTLED",await(payments.start(ENTERPRISE,deposit)).status());
    }

    @Test void simultaneousCallbacksCreditOnlyOnce() {
        Deposit deposit=deposit(account(),"10"); await(payments.start(ENTERPRISE,deposit));
        var first=confirm(deposit).subscribeAsCompletionStage(); var second=confirm(deposit).subscribeAsCompletionStage();
        assertEquals(first.toCompletableFuture().join(),second.toCompletableFuture().join());
        assertEquals("10.00000000",balance(deposit.walletId()));
        assertEquals(1,await(wallet.history(ENTERPRISE,deposit.walletId(),"POINTS",0,50)).size());
    }

    @Test void providerPaymentCannotCreditTwoIntents() {
        Deposit a=deposit(account(),"3"),b=deposit(account(),"3");
        gateway.failStart=true;
        try {
            assertThrows(IllegalStateException.class,() -> await(payments.start(ENTERPRISE,a)));
            assertThrows(IllegalStateException.class,() -> await(payments.start(ENTERPRISE,b)));
        } finally { gateway.failStart=false; }
        String shared="same-provider-payment-"+UUID.randomUUID();
        var first=payments.confirm(ENTERPRISE,"fixture","merchant",callback(a,"3","POINTS",shared)).subscribeAsCompletionStage();
        var second=payments.confirm(ENTERPRISE,"fixture","merchant",callback(b,"3","POINTS",shared)).subscribeAsCompletionStage();
        int successes=0;
        try { first.toCompletableFuture().join(); successes++; } catch(RuntimeException expected) { }
        try { second.toCompletableFuture().join(); successes++; } catch(RuntimeException expected) { }
        assertEquals(1,successes);
        assertEquals(0,new java.math.BigDecimal(balance(a.walletId())).add(new java.math.BigDecimal(balance(b.walletId()))).compareTo(new java.math.BigDecimal("3")));
    }

    @Test void concurrentSpendingCannotOverdrawConfirmedPayment() {
        UUID a=account(),b=account(); Deposit deposit=deposit(a,"10"); await(payments.start(ENTERPRISE,deposit)); await(confirm(deposit));
        var first=wallet.move(ENTERPRISE,Action.TRANSFER,movement(a,b,"7")).subscribeAsCompletionStage();
        var second=wallet.move(ENTERPRISE,Action.WITHDRAWAL,movement(a,clearing,"7")).subscribeAsCompletionStage();
        int successes=0;
        try { first.toCompletableFuture().join(); successes++; } catch(RuntimeException expected) { }
        try { second.toCompletableFuture().join(); successes++; } catch(RuntimeException expected) { }
        assertEquals(1,successes); assertEquals("3.00000000",balance(a));
    }

    @Test void settlementFailureRollsBackWalletAndReferenceTogether() throws Exception {
        Deposit deposit=deposit(account(),"11"); gateway.failStart=true;
        try { assertThrows(IllegalStateException.class,() -> await(payments.start(ENTERPRISE,deposit))); }
        finally { gateway.failStart=false; }
        UUID id=intentId(identity.enterpriseId(),deposit.operationKey());
        sql("create function public.payment_test_failure() returns trigger language plpgsql as $$ begin "
            +"if NEW.parenteventid='"+id+"' then raise exception 'injected settlement failure'; end if; return NEW; end $$");
        sql("create trigger payment_test_failure before insert on event.eventxevent for each row execute function public.payment_test_failure()");
        try { assertThrows(RuntimeException.class,() -> await(confirm(deposit))); }
        finally { sql("drop trigger payment_test_failure on event.eventxevent"); sql("drop function public.payment_test_failure()"); }
        assertEquals("0.00000000",balance(deposit.walletId()));
        Attempt saved=await(payments.get(ENTERPRISE,deposit.operationKey()));
        assertNull(saved.reference()); assertNull(saved.eventId());
        await(confirm(deposit)); assertEquals("11.00000000",balance(deposit.walletId()));
    }

    private void grantProvider(String provider,UUID system,List<String> actions) throws Exception {
        scoped.install(system,"WORK",identity.enterpriseId(),provider);
        for(String action:actions) {
            scoped.grant(system,"WORK",identity.enterpriseId(),provider,identity.partyId(),action);
        }
    }
    /** Test-only HMAC adapter. No real provider signature/settlement claims. */
    private class SignedGateway implements PaymentGateway {
        volatile boolean failStart;
        int starts;
        public Uni<Checkout> start(Attempt attempt) {
            starts++;
            // A separate transaction can see the Event: external work starts only after intent commit.
            return factory.withStatelessTransaction(session -> session.createNativeQuery("select eventid from event.event where eventid=:id",UUID.class)
                .setParameter("id",attempt.id()).getSingleResult())
                .chain(id -> failStart ? Uni.createFrom().failure(new IllegalStateException("Unknown provider outcome")) :
                    Uni.createFrom().item(new Checkout("provider-"+id,URI.create("https://checkout.example.test/"+id))));
        }
        public Uni<Confirmation> verify(String merchant,Callback callback) {
            return Uni.createFrom().item(() -> {
                String signature=callback.headers().getOrDefault("signature",List.of("")).getFirst();
                if(!MessageDigest.isEqual(signature.getBytes(StandardCharsets.US_ASCII),sign(callback.body()).getBytes(StandardCharsets.US_ASCII)))
                    throw new SecurityException("Invalid provider signature");
                String[] parts=new String(callback.body(),StandardCharsets.UTF_8).split("\\|",-1);
                if(parts.length!=5 || !merchant.equals(parts[1])) throw new SecurityException("Invalid provider confirmation");
                return new Confirmation(UUID.fromString(parts[0]),parts[1],parts[2],parts[3],parts[4]);
            });
        }
    }
    private Callback callback(Deposit deposit,String amount,String unit,String reference) {
        byte[] body=(intentId(identity.enterpriseId(),deposit.operationKey())+"|merchant|"+reference+"|"+amount+"|"+unit).getBytes(StandardCharsets.UTF_8);
        return new Callback(body,Map.of("signature",List.of(sign(body))));
    }
    private static String sign(byte[] body) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec("test-only-provider-key".getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
    private String reference(Deposit deposit) { return "provider-"+intentId(identity.enterpriseId(),deposit.operationKey()); }
    private Uni<Receipt> confirm(Deposit deposit) { return payments.confirm(ENTERPRISE,"fixture","merchant",callback(deposit,deposit.amount(),deposit.unit(),reference(deposit))); }
    private UUID account() { return await(wallet.create(ENTERPRISE,new Create(UUID.randomUUID()))).arrangementId(); }
    private Deposit deposit(UUID wallet,String amount) { return new Deposit(UUID.randomUUID(),wallet,amount,"POINTS"); }
    private Movement movement(UUID from,UUID to,String amount) { return new Movement(UUID.randomUUID(),from,to,amount,"POINTS"); }
    private String balance(UUID id) { return new java.math.BigDecimal(await(wallet.balance(ENTERPRISE,id,"POINTS")).amount()).setScale(8).toPlainString(); }
    private static <T> T await(Uni<T> value) { return value.await().atMost(Duration.ofSeconds(90)); }
    private void sql(String sql) throws Exception {
        var result=postgres.execInContainer("psql","-v","ON_ERROR_STOP=1","-U",postgres.getUsername(),"-d",postgres.getDatabaseName(),"-c",sql);
        assertEquals(0,result.getExitCode(),result.getStderr());
    }
}
