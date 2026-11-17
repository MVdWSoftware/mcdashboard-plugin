package org.mcdashboard.web;

import org.mcdashboard.auth.RateLimiter;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Which clients may use the API, and how often. The official app identifies itself with
 * {@code User-Agent: MCDashboard/1.2.3 (android)}; the owner can allow more patterns and require a minimum version.
 * The User-Agent identifies and versions a client, it is not a credential: a custom client can copy it, which is why the
 * request limits key on the paired device and not on anything the client says about itself.
 */
public final class ClientPolicy {
    /** The official app: {@code MCDashboard/x.y.z (android|ios|dev)}, the version optionally with a {@code -suffix}. Same as the protocol package. */
    public static final Pattern OFFICIAL = Pattern.compile("MCDashboard/(\\d+\\.\\d+\\.\\d+)(?:-[\\w.]+)? \\((android|ios|dev)\\)");
    private static final int MAX_AGENT = 80;

    /** An identified client. {@code version} and {@code platform} are null for a client the owner allowed explicitly. */
    public record Client(String agent, String version, String platform) {
    }

    private final List<Pattern> extra;
    private final int[] minVersion;
    private final String minVersionText;
    private final RateLimiter devices;
    private final RateLimiter addresses;

    /**
     * @param extra          patterns (matched against the whole header) of further allowed clients
     * @param minVersion     oldest accepted official version {@code x.y.z}, or null/blank for none
     * @param devices        limiter for authenticated requests, keyed by device id
     * @param addresses      limiter for requests that are not authenticated, keyed by remote address
     */
    public ClientPolicy(List<Pattern> extra, String minVersion, RateLimiter devices, RateLimiter addresses) {
        this.extra = List.copyOf(extra);
        int[] parsed = minVersion == null || minVersion.isBlank() ? null : parse(minVersion.trim());
        if (minVersion != null && !minVersion.isBlank() && parsed == null) {
            throw new IllegalArgumentException("min-client-version must look like 1.2.3");
        }
        this.minVersion = parsed;
        this.minVersionText = parsed == null ? null : minVersion.trim();
        this.devices = devices;
        this.addresses = addresses;
    }

    /** The default: official app only, no minimum version, 120 requests a minute per device and 60 per address. */
    public static ClientPolicy defaults(Clock clock) {
        return new ClientPolicy(List.of(), null, new RateLimiter(clock, 120, 60_000), new RateLimiter(clock, 60, 60_000));
    }

    /**
     * Builds the policy from the config values; a pattern that does not compile is reported and skipped (it can only
     * make the policy stricter), a bad minimum version is reported and ignored.
     */
    public static ClientPolicy of(Clock clock, List<String> allowed, String minVersion, int perDevice, int perAddress,
                                  Consumer<String> warn) {
        List<Pattern> extra = new ArrayList<>();
        for (String regex : allowed) {
            try {
                extra.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                warn.accept("security.allowed-clients: ignoring invalid pattern '" + regex + "'");
            }
        }
        String min = minVersion;
        if (min != null && !min.isBlank() && parse(min.trim()) == null) {
            warn.accept("security.min-client-version must look like 1.2.3; ignoring '" + min + "'");
            min = null;
        }
        return new ClientPolicy(extra, min, new RateLimiter(clock, Math.max(1, perDevice), 60_000),
                new RateLimiter(clock, Math.max(1, perAddress), 60_000));
    }

    /** The client behind a {@code User-Agent}, or empty when it is neither the official app nor allowed by the owner. */
    public Optional<Client> identify(String userAgent) {
        if (userAgent == null) return Optional.empty();
        Matcher m = OFFICIAL.matcher(userAgent);
        if (m.matches()) return Optional.of(new Client(userAgent, m.group(1), m.group(2)));
        for (Pattern p : extra) {
            if (p.matcher(userAgent).matches()) {
                return Optional.of(new Client(userAgent.substring(0, Math.min(MAX_AGENT, userAgent.length())), null, null));
            }
        }
        return Optional.empty();
    }

    /** Whether an official client is older than the configured minimum. Allowed third-party clients are never "too old". */
    public boolean tooOld(Client client) {
        if (minVersion == null || client.version() == null) return false;
        int[] v = parse(client.version());
        if (v == null) return false;
        for (int i = 0; i < 3; i++) {
            if (v[i] != minVersion[i]) return v[i] < minVersion[i];
        }
        return false;
    }

    /** The configured minimum version, or null. */
    public String minVersion() {
        return minVersionText;
    }

    /** Records a request of a paired device; false when it is over its limit. */
    public boolean allowDevice(String deviceId) {
        return devices.allow(deviceId);
    }

    /** Records a request that is not authenticated; false when the address is over its limit. */
    public boolean allowAddress(String address) {
        return addresses.allow(address);
    }

    private static int[] parse(String version) {
        String[] parts = version.split("\\.", -1);
        if (parts.length != 3) return null;
        int[] out = new int[3];
        try {
            for (int i = 0; i < 3; i++) {
                if (!parts[i].matches("\\d{1,9}")) return null;
                out[i] = Integer.parseInt(parts[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }
}
