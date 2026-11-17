package org.mcdashboard.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AuthTest {
    private static final byte[] SECRET = new byte[48];

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void jwtRoundTripAndExpiry() {
        MutableClock clock = new MutableClock();
        TokenService t = new TokenService(SECRET, clock);
        String token = t.issue(TokenService.Type.ACCESS, "dev1", "uuid1", 3, 60);
        assertEquals(3, token.split("\\.").length, "standard JWT shape");
        var claims = t.verify(token, TokenService.Type.ACCESS).orElseThrow();
        assertEquals("dev1", claims.deviceId());
        assertEquals(3, claims.serial());
        assertTrue(t.verify(token, TokenService.Type.REFRESH).isEmpty(), "type is enforced");
        clock.now = clock.now.plus(Duration.ofSeconds(61));
        assertTrue(t.verify(token, TokenService.Type.ACCESS).isEmpty(), "expired");
    }

    @Test
    void jwtRejectsTamperingAndAlgNone() {
        TokenService t = new TokenService(SECRET, new MutableClock());
        String token = t.issue(TokenService.Type.ACCESS, "dev1", "uuid1", 1, 60);
        String[] p = token.split("\\.");
        assertTrue(t.verify(p[0] + "." + p[1] + "." + p[2].substring(0, p[2].length() - 2) + "AA", TokenService.Type.ACCESS).isEmpty());
        String none = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
        assertTrue(t.verify(none + "." + p[1] + ".", TokenService.Type.ACCESS).isEmpty());
        assertTrue(new TokenService(new byte[]{1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,32}, new MutableClock())
                .verify(token, TokenService.Type.ACCESS).isEmpty(), "other secret");
    }

    @Test
    void otpIsSingleUseAndExpires() {
        MutableClock clock = new MutableClock();
        OtpService o = new OtpService(clock, 60);
        String code = o.create("u1", "Steve");
        assertEquals(8, code.length());
        assertTrue(o.redeem(code.toLowerCase()).isPresent());
        assertTrue(o.redeem(code).isEmpty(), "single use");
        String c2 = o.create("u1", "Steve");
        clock.now = clock.now.plusSeconds(61);
        assertTrue(o.redeem(c2).isEmpty(), "expired");
    }

    @Test
    void codesAreShownInGroupsButRedeemWithOrWithoutTheHyphen() {
        OtpService o = new OtpService(new MutableClock(), 60);
        String code = o.create("u1", "Steve");
        assertEquals(code.substring(0, 4) + "-" + code.substring(4), OtpService.display(code));
        assertTrue(o.redeem(OtpService.display(code).toLowerCase()).isPresent());
    }

    @Test
    void rateLimiterBlocksAfterMax() {
        MutableClock clock = new MutableClock();
        RateLimiter r = new RateLimiter(clock, 2, 1000);
        assertTrue(r.allow("a"));
        assertTrue(r.allow("a"));
        assertFalse(r.allow("a"));
        assertTrue(r.allow("b"));
        clock.now = clock.now.plusMillis(1001);
        assertTrue(r.allow("a"));
    }
}
