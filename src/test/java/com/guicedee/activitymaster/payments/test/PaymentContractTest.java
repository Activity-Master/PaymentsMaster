package com.guicedee.activitymaster.payments.test;

import com.guicedee.activitymaster.payments.*;
import com.guicedee.activitymaster.wallet.WalletIdentityProvider;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.guicedee.activitymaster.payments.PaymentModels.*;

class PaymentContractTest {
    @Test void defaultsDenyInsteadOfInventingHostIdentityOrProvider() {
        assertThrows(SecurityException.class,() -> new WalletIdentityProvider.Deny().current().await().atMost(Duration.ofSeconds(1)));
        assertThrows(SecurityException.class,() -> new PaymentHost.Deny().route(null,null).await().atMost(Duration.ofSeconds(1)));
        assertThrows(SecurityException.class,() -> new PaymentHost.Deny().currentActor(null).await().atMost(Duration.ofSeconds(1)));
        assertThrows(SecurityException.class,() -> new PaymentGateways.Deny().require("browser-selected"));
    }
    @Test void decimalAmountsAndStableOperationKeysAreExact() {
        UUID operation=UUID.randomUUID(),wallet=UUID.randomUUID(),enterprise=UUID.randomUUID();
        assertEquals(new Deposit(operation,wallet,"12.50","USD"),new Deposit(operation,wallet,"12.5000","USD"));
        for(String invalid:List.of("1e2","0","-1","0.000000001","NaN","1,23"))
            assertThrows(IllegalArgumentException.class,() -> new Deposit(operation,wallet,invalid,"USD"));
        assertEquals(intentId(enterprise,operation),intentId(enterprise,operation));
        assertNotEquals(intentId(enterprise,operation),intentId(UUID.randomUUID(),operation));
    }
    @Test void rawCallbackDataCannotBeChangedAfterAdmission() {
        byte[] bytes={1,2}; List<String> signature=new ArrayList<>(List.of("verified"));
        Map<String,List<String>> headers=new HashMap<>(); headers.put("signature",signature);
        Callback callback=new Callback(bytes,headers);
        bytes[0]=9; signature.clear(); headers.clear(); callback.body()[0]=8;
        assertArrayEquals(new byte[]{1,2},callback.body());
        assertEquals(List.of("verified"),callback.headers().get("signature"));
        assertEquals("Callback[redacted]",callback.toString());
    }
    @Test void gatewayCheckoutMustBeHttpsWithoutEmbeddedCredentials() {
        assertThrows(IllegalArgumentException.class,() -> new Checkout("p",URI.create("http://example.test")));
        assertThrows(IllegalArgumentException.class,() -> new Checkout("p",URI.create("https://user:secret@example.test")));
        assertThrows(IllegalArgumentException.class,() -> new Checkout("p",URI.create("javascript:alert(1)")));
    }
}
