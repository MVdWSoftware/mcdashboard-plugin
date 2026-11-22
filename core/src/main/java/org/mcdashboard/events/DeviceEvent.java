/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.events;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Something that happened to a paired device. {@code epoch} identifies one run of the plugin and {@code seq}
 * counts events within it, so {@code epoch:seq} orders events and lets a consumer resume after a gap.
 *
 * @param epoch A hex ID identifying this plugin run.
 * @param seq Monotonically increasing sequence number within the epoch.
 * @param type The event type, such as {@code device.revoked}.
 * @param timeMs Epoch milliseconds when the event occurred.
 * @param deviceId Unique identifier of the paired device.
 * @param player UUID of the owning player.
 */
public record DeviceEvent(String epoch, long seq, String type, long timeMs, String deviceId, String player) {
    public static final String REVOKED = "device.revoked";

    /** The unique event identifier as {@code epoch:seq}.
     *
     * @return The identifier string.
     */
    public String id() {
        return epoch + ":" + seq;
    }

    /** The JSON body shared by the stream and the webhooks (see {@code DeviceRevokedEvent} in openapi.yaml).
     *
     * @return A map with keys matching the SSE event format.
     */
    public Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("epoch", epoch);
        m.put("seq", seq);
        m.put("time", timeMs / 1000);
        m.put("deviceId", deviceId);
        m.put("player", player);
        return m;
    }
}
