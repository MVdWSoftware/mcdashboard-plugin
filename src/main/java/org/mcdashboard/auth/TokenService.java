package org.mcdashboard.auth;

import org.mcdashboard.json.Json;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Issues and verifies standard JWTs (RFC 7519, HS256, {@code header.payload.signature}).
 * Claims: {@code sub} = device id, {@code player} = player UUID, {@code typ} = access|refresh,
 * {@code ser} = refresh generation, {@code iat}/{@code exp} in seconds. Access tokens are
 * stateless, so a proxy or other node holding the secret can validate them; refresh tokens also
 * carry a serial checked against the {@link DeviceStore} so they can be revoked and rotated.
 */
public final class TokenService {
    public enum Type { ACCESS, REFRESH }

    /** Verified token contents. */
    public record Claims(Type type, String deviceId, String player, long serial, long expiresAtMs) {
    }

    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder UNB64 = Base64.getUrlDecoder();

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final byte[] secret;
    private final Clock clock;

    public TokenService(byte[] secret, Clock clock) {
        if (secret.length < 32) throw new IllegalArgumentException("secret must be at least 32 bytes");
        this.secret = secret.clone();
        this.clock = clock;
    }

    /** Creates a signed token valid for {@code ttlSeconds} (or forever when negative). */
    public String issue(Type type, String deviceId, String player, long serial, long ttlSeconds) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sub", deviceId);
        payload.put("player", player);
        payload.put("typ", type == Type.ACCESS ? "access" : "refresh");
        payload.put("ser", serial);
        payload.put("iat", clock.millis() / 1000);
        if (ttlSeconds >= 0) payload.put("exp", clock.millis() / 1000 + ttlSeconds);
        String body = B64.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(Json.write(payload).getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(sign(body));
    }

    /** Verifies signature and expiry; empty when the token is invalid, tampered with or expired. */
    public Optional<Claims> verify(String token, Type expected) {
        if (token == null) return Optional.empty();
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) return Optional.empty();
        try {
            Map<String, Object> header = Json.parseObject(new String(UNB64.decode(parts[0]), StandardCharsets.UTF_8));
            if (!"HS256".equals(header.get("alg"))) return Optional.empty(); // rejects alg=none and algorithm confusion
            String signed = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(UNB64.decode(parts[2]), sign(signed))) return Optional.empty();
            Map<String, Object> p = Json.parseObject(new String(UNB64.decode(parts[1]), StandardCharsets.UTF_8));
            Type type = "access".equals(p.get("typ")) ? Type.ACCESS : Type.REFRESH;
            if (type != expected) return Optional.empty();
            long exp = p.get("exp") instanceof Number n ? n.longValue() * 1000 : -1;
            if (exp >= 0 && exp < clock.millis()) return Optional.empty();
            return Optional.of(new Claims(type, (String) p.get("sub"), (String) p.get("player"),
                    ((Number) p.get("ser")).longValue(), exp));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
