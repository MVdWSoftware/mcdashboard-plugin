package org.mcdashboard.events;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process fan-out of {@link DeviceEvent}s with a short replay history. Publishing never blocks: listeners
 * must only enqueue. A consumer that reconnects with the last id it saw gets what it missed, or a reset when
 * that is no longer possible (plugin restarted, or the history moved past it), and must then assume it missed events.
 */
public final class EventBus {
    private static final int HISTORY = 256;

    private final String epoch;
    private final Clock clock;
    private final ArrayDeque<DeviceEvent> history = new ArrayDeque<>();
    private final List<Consumer<DeviceEvent>> listeners = new CopyOnWriteArrayList<>();
    private long seq;

    public EventBus(Clock clock) {
        this.clock = clock;
        byte[] raw = new byte[4];
        new SecureRandom().nextBytes(raw);
        this.epoch = HexFormat.of().formatHex(raw);
    }

    public String epoch() {
        return epoch;
    }

    public synchronized DeviceEvent publish(String type, String deviceId, String player) {
        DeviceEvent e = new DeviceEvent(epoch, ++seq, type, clock.millis(), deviceId, player);
        history.addLast(e);
        if (history.size() > HISTORY) history.removeFirst();
        for (Consumer<DeviceEvent> l : listeners) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // one broken listener must not stop revocation or the other listeners
            }
        }
        return e;
    }

    /**
     * Registers a listener after first handing it what it missed since {@code lastEventId}. Replay and
     * registration are atomic, so no event is lost or duplicated in between.
     *
     * @param onReset called instead of a replay when continuity cannot be guaranteed
     * @return unsubscribes
     */
    public synchronized Runnable subscribe(String lastEventId, Consumer<DeviceEvent> onEvent, Runnable onReset) {
        if (lastEventId != null && !lastEventId.isBlank()) {
            List<DeviceEvent> missed = missedSince(lastEventId.trim());
            if (missed == null) onReset.run();
            else missed.forEach(onEvent);
        }
        listeners.add(onEvent);
        return () -> listeners.remove(onEvent);
    }

    /** Events after the id, or null when the id is from another run or no longer covered by the history. */
    private List<DeviceEvent> missedSince(String lastEventId) {
        int c = lastEventId.indexOf(':');
        if (c <= 0 || !lastEventId.substring(0, c).equals(epoch)) return null;
        long last;
        try {
            last = Long.parseLong(lastEventId.substring(c + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        long oldest = history.isEmpty() ? seq + 1 : history.peekFirst().seq();
        if (last > seq || last + 1 < oldest) return null;
        List<DeviceEvent> out = new ArrayList<>();
        for (DeviceEvent e : history) if (e.seq() > last) out.add(e);
        return out;
    }
}
