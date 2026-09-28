package com.guicedee.activitymaster.payments;

import com.guicedee.activitymaster.wallet.WalletIdentity;
import com.guicedee.activitymaster.wallet.WalletModels.Movement;
import com.guicedee.activitymaster.fsdm.transactions.ActivityScope.Context;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Public monetary values are decimal strings. Identity and routing are host-only values. */
public final class PaymentModels {
    private PaymentModels() { }
    public record Deposit(UUID operationKey, UUID walletId, String amount, String unit) {
        public Deposit {
            Objects.requireNonNull(operationKey); Objects.requireNonNull(walletId);
            amount = decimal(amount);
            com.guicedee.activitymaster.wallet.WalletModels.requireUnit(unit);
        }
    }
    public record Route(String gateway, String merchant, UUID clearingId, String paymentProviderId,
                        UUID merchantPartyId, UUID providerPartyId) {
        public Route {
            gateway = identifier(gateway); merchant = identifier(merchant);
            Objects.requireNonNull(clearingId); paymentProviderId = identifier(paymentProviderId);
            Objects.requireNonNull(merchantPartyId); Objects.requireNonNull(providerPartyId);
        }
    }
    /** Persisted attribution, never a credential or proof of current authorization. */
    public record Actor(UUID partyId, UUID enterpriseId, Context context, String walletProviderId) {
        public Actor {
            Objects.requireNonNull(partyId); Objects.requireNonNull(enterpriseId); Objects.requireNonNull(context);
            walletProviderId = identifier(walletProviderId);
        }
        public static Actor from(WalletIdentity identity) {
            return new Actor(identity.partyId(), identity.enterpriseId(), identity.context(), identity.providerId());
        }
    }
    public record Attempt(UUID id, Actor actor, Route route, Deposit deposit, String reference, UUID eventId) {
        public Attempt {
            Objects.requireNonNull(id); Objects.requireNonNull(actor); Objects.requireNonNull(route); Objects.requireNonNull(deposit);
            if (reference != null) reference = identifier(reference);
        }
        public Movement movement() {
            return new Movement(key("movement", id), route.clearingId(), deposit.walletId(), deposit.amount(), deposit.unit());
        }
        public String status() { return eventId == null ? "PENDING" : "SETTLED"; }
    }
    public record Checkout(String reference, URI url) {
        public Checkout {
            reference = identifier(reference); Objects.requireNonNull(url);
            if (!"https".equalsIgnoreCase(url.getScheme()) || url.getHost() == null || url.getUserInfo() != null)
                throw new IllegalArgumentException("HTTPS checkout URL required");
        }
    }
    public record Started(UUID paymentId, String status, Checkout checkout) { }
    /** Only a gateway that has authenticated final provider confirmation may produce this. */
    public record Confirmation(UUID paymentId, String merchant, String reference, String amount, String unit) {
        public Confirmation {
            Objects.requireNonNull(paymentId); merchant = identifier(merchant); reference = identifier(reference);
            amount = decimal(amount); com.guicedee.activitymaster.wallet.WalletModels.requireUnit(unit);
        }
    }
    public record Callback(byte[] body, Map<String, List<String>> headers) {
        public Callback {
            body = Objects.requireNonNull(body).clone();
            Map<String, List<String>> copy = new HashMap<>();
            Objects.requireNonNull(headers).forEach((key, value) -> copy.put(key, List.copyOf(value)));
            headers = Map.copyOf(copy);
        }
        @Override public byte[] body() { return body.clone(); }
        @Override public String toString() { return "Callback[redacted]"; }
    }
    public static UUID intentId(UUID enterprise, UUID operation) { return key("intent:" + enterprise, operation); }
    private static UUID key(String kind, UUID id) {
        return UUID.nameUUIDFromBytes(("payment-master:" + kind + ":" + id).getBytes(StandardCharsets.UTF_8));
    }
    private static String decimal(String value) {
        if (value == null || !value.matches("[0-9]{1,30}(\\.[0-9]{1,8})?") || new BigDecimal(value).signum() <= 0)
            throw new IllegalArgumentException("Positive decimal string required (up to 8 fractional digits)");
        return new BigDecimal(value).stripTrailingZeros().toPlainString();
    }
    static String identifier(String value) {
        if (value == null || value.isBlank() || value.length() > 255 || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Bounded nonblank identifier required");
        return value;
    }
}
