# Host integration

Depend on `com.activity-master:payment-master:3.0.0-SNAPSHOT`. JPMS and service files
register the binder, scanner, Payment Master system and enterprise update. Wallet
Master is a transitive dependency. No host authentication or concrete provider SDK
belongs in this reusable module.

## Bindings

- `WalletIdentityProvider`: fresh verified actor, authorized enterprise/context,
  reviewed wallet provider and ActivityMaster identifying credential. Never use
  SecurityToken row IDs, bearer strings, browser identity fields or system credentials.
- `PaymentHost`: trusted merchant/clearing/provider configuration through `route`;
  `currentActor` re-resolves the current initiating account for callbacks, including
  revocation. Route configuration supplies canonical merchant and processor
  InvolvedParty IDs for classified participant relationships. Those parties must be
  readable in the authorized enterprise. Stored party IDs are lookup hints, never
  authentication. Credentials are not persisted. Both default methods deny.
- `PaymentGateways`: allowlisted external processor adapters. These processor IDs
  are distinct from reviewed ActivityMaster plugin provider IDs. Default denies.

`PaymentApi.start(enterprise, Deposit)` and `get(enterprise, operationKey)` resolve
the authenticated host identity. Deposit contains operation key, destination wallet,
decimal-string amount and unit only. Host policy supplies the clearing arrangement,
merchant and reviewed Payment Master provider ID.

`confirm(enterprise, gateway, merchant, Callback)` is a server integration entry
point. Choose its route arguments from server configuration, retain exact body bytes
and signature headers, and acknowledge only after its Uni succeeds. Do not expose
this as a browser success endpoint. The host owns HTTP limits, authentication,
callback routes and scheduled reconciliation; no automatic polling job is installed.

## Provider contract and recovery

`PaymentGateway.start` must use the immutable intent ID as provider idempotency key.
Repeated calls after failure/crash must address the same payment. The committed
PENDING intent is the durable retry work item; retry the same original request.
Network failure is an unknown outcome, not proof of a failed payment.

`verify` must verify signature/authenticity, intended merchant, timestamp/replay rules
and final successful capture/settlement; query the provider when required. Return
the original intent ID, merchant, stable payment reference, amount and unit only
after verification. Reject pending/failed/cancelled/unverified messages. Browser
redirects never confirm payments. No permissive or generic verifier is supplied.

Provider calls run outside DB transactions. Callback may arrive before checkout
binding. Both bind the same immutable reference. A merchant/reference advisory lock
and historical FSDM reference classification prevent one provider payment crediting
two intents through the service. Stable namespaced wallet operation keys
derive from intent IDs, never delivery IDs. Current route, identity, grants and row
access are rechecked on retries, including settled confirmations. Settlement and
wallet movement commit or roll back together.

Revocation after provider capture blocks credit and requires authorized operational
reconciliation/refund; it must never trigger a system-credential authorization bypass.

## Provisioning

1. Use the existing FSDM domains and Wallet Master's existing transaction infrastructure.
   Payment Master adds no schema or tables. Use the ActivityMaster domain-write
   connection, not registry discovery credentials.
2. Run the existing enterprise `ISystemUpdate` lifecycle: Wallet update 1200 seeds
   taxonomy; Payment update 1300 registers Payment Master and seeds its Event
   types and concept-scoped classifications. Neither runs DDL or grants access.
   Clearing arrangements are provisioned separately by an authorized host.
3. Through an authenticated, administrator-authorized FSDM provisioning flow,
   create one active Event of type `<SystemID>:Scoped Provider Installation` for
   each reviewed `(system, enterprise, Realm, owner, provider)` installation.
   Classify it with the system-qualified `ScopedProvider`, `ScopedRealm`, and
   `ScopedOwner` EventXClassification roles. Give intended actors FSDM read
   permission on this Event. Ending its effective period disables the provider.
   Installation by itself grants no behavior.
4. In the same authorized provisioning flow, create an active Event of type
   `<SystemID>:Scoped Behavior Grant` for each granted action. Give it the same
   provider/Realm/owner classifications, a `ScopedBehavior` value of
   `<SystemID>:<action>`, and a `ScopedActor` EventXInvolvedParty link to the
   grantee. Grant that actor FSDM read permission on the grant Event. One grant
   Event may link several actors; separate grant Events remain independent.
   Ending the Event or actor link's effective period revokes it. Payment Master
   actions are `payment.create`, `payment.read`, `payment.confirm`. Wallet
   movements independently need `wallet.post` and
   `wallet.deposit` plus write access to both arrangements. Creation admission also
   requires wallet read access to the destination and checks posting/deposit grants
   and write access to both arrangements before checkout. Keep restricted FSDM security.
5. Configure a Wallet Clearing arrangement per authorized merchant/context flow.
   Personal/Social participant restrictions still apply. No browser clearing source
   or merchant credentials are accepted.

Wallet Master's REST/GraphQL adapters remain available. Payment Master exposes a
typed Java host API, avoiding accidental publication of an unsigned callback route.
Local tests do not prove live host identity binding, deployed migrations or real
provider signatures/checkout/callbacks. Those require a concrete host/provider.

## FSDM mapping

| Payment concept | Canonical domain |
|---|---|
| Intent | Event with Payment Attempt EventType |
| Operation, Realm/context, gateway, merchant account, amount/unit | Concept-scoped EventXClassification |
| Initiating actor, merchant, processor | EventXInvolvedParty with reviewed roles |
| Clearing and destination | EventXArrangement with reviewed roles |
| Provider reference and deduplication claim | EventXClassification, retained across lifecycle changes |
| Settlement | EventXEvent linking payment parent to Wallet movement child |
| Pending/settled status | Derived from absence/presence of the settlement link |

All relationship rows have restricted FSDM security and explicit actor read grants;
the Event has actor read/write access. Payment operations recheck those grants.
No payment SQL migration, DDL, tables or persistence unit is introduced. Shared
`EventXEvent` taxonomy is additive; existing classification concepts retain their names.

## Local verification (28 September 2026)

Run from `ActivityMaster/payments`, with Docker available for the existing shared
PostgreSQL test harness:

```powershell
mvn -B -o '-DFSDM_DBSERVER=127.0.0.1' '-DFSDM_PASSWORD=fixture-password' '-DFSDM_SSL_MODE=disable' '-DENVIRONMENT=test' '-Dmaven.test.failure.ignore=false' test
```

22 tests passed: 18 production-service PostgreSQL integration tests and 4 contract
tests. The fixture creates only canonical FSDM and transaction tables; it does
not create a `space` schema. Coverage includes current identity and token-row-ID
rejection, wrong scope, provider-qualified grants and revocation, Event/relationship/Arrangement row denial,
confirmed deposit plus wallet transfer/withdrawal, duplicate/concurrent callbacks,
one provider payment claimed by concurrent intents, concurrent spending, recovery
after an unknown checkout outcome, transaction rollback after wallet posting,
idempotent installation and absence of a payments schema. Provider authenticity is
tested using an explicitly test-only HMAC adapter, not a real provider SDK.

The existing WalletIntegrationTest also passed all 6 tests against the locally
built shared dependencies. Client/core/wallet/payment artifacts and the BOM were
installed locally. No live host service, provider account, callback route or production
database was configured or verified. External payout/refund/reservation workflows
remain outside this inbound-payment module implementation.
