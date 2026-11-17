/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mcdashboard.TestContexts;
import org.mcdashboard.auth.*;
import org.mcdashboard.events.DeviceEvent;
import org.mcdashboard.events.EventBus;
import org.mcdashboard.events.EventStream;
import org.mcdashboard.json.Json;
import org.mcdashboard.placeholder.*;

class EventStreamApiTest {
    static final String TOKEN = "events-token-0123456789";
    HttpApi api;
    DeviceStore devices;
    EventBus bus;
    EventStream stream;
    String base;
    HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        Clock clock = Clock.systemUTC();
        var defs = PlaceholderDefinition.parseAll(List.of(Map.of("id", "online", "value", "%server_online%", "type", "INTEGER")), m -> fail(m));
        devices = new DeviceStore(Files.createTempFile("devices", ".json"));
        var ctx = TestContexts.context("Test", "1.0", () -> ServerStatus.EMPTY, false, 2, 900, 3600, 0,
                new PlaceholderService((t, u) -> "7", clock, 1000, defs), new TokenService(new byte[48], clock),
                devices, new OtpService(clock, 60), new RateLimiter(clock, 100, 60000), List.of(), Set.of(), new HistoryService(clock, defs), null, d -> {}, TestContexts.allowAll(), "en");
        api = new HttpApi(ctx);
        bus = new EventBus(clock);
        devices.onRemoved(d -> bus.publish(DeviceEvent.REVOKED, d.id(), d.playerUuid()));
        stream = new EventStream(bus, 1);
    }

    void listen(boolean enabled) throws Exception {
        if (enabled) api.events(stream, TOKEN);
        api.start("127.0.0.1", 0, null);
        base = "http://127.0.0.1:" + api.port();
    }

    @AfterEach
    void stop() {
        stream.close();
        api.stop();
    }

    HttpResponse<InputStream> open(String token, String lastEventId) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + "/v1/events"));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (lastEventId != null) b.header("Last-Event-ID", lastEventId);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** Reads frames until one with this event name arrives; returns its id and data. */
    static String[] frame(BufferedReader in, String event) throws Exception {
        String id = null, name = null, data = null, line;
        while ((line = in.readLine()) != null) {
            if (line.startsWith("id: ")) id = line.substring(4);
            else if (line.startsWith("event: ")) name = line.substring(7);
            else if (line.startsWith("data: ")) data = line.substring(6);
            else if (line.isEmpty() && name != null) {
                if (name.equals(event)) return new String[]{id, data};
                id = name = data = null;
            }
        }
        return fail("stream ended before event " + event);
    }

    @Test
    void theEndpointDoesNotExistUnlessAnEventsTokenIsConfigured() throws Exception {
        listen(false);
        assertEquals(404, open(TOKEN, null).statusCode());
    }

    @Test
    void itNeedsTheToken() throws Exception {
        listen(true);
        assertEquals(401, open(null, null).statusCode());
        assertEquals(401, open("wrong-token-0123456789", null).statusCode());
        assertEquals(200, open(TOKEN, null).statusCode());
    }

    @Test
    void anOpenStreamDoesNotStarveTheRestOfTheApi() throws Exception {
        listen(true);
        var open = new ArrayList<InputStream>();
        for (int i = 0; i < 6; i++) open.add(open(TOKEN, null).body()); // more streams than the API has worker threads
        for (String path : List.of("/v1/info", "/v1/values?ids=online")) {
            var r = http.send(HttpRequest.newBuilder(URI.create(base + path)).header("User-Agent", "MCDashboard/1.0.0 (dev)").timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode(), path);
        }
        var del = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/devices/self")).header("User-Agent", "MCDashboard/1.0.0 (dev)").timeout(Duration.ofSeconds(5)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(401, del.statusCode());
        for (var in : open) in.close();
    }

    @Test
    void revokingADeviceReachesTheStreamAndResumeReplaysWhatWasMissed() throws Exception {
        listen(true);
        var d1 = devices.add("uuid-1", "Steve", "Pixel", 5).orElseThrow();
        var d2 = devices.add("uuid-1", "Steve", "Tablet", 5).orElseThrow();
        var r = open(TOKEN, null);
        assertEquals("text/event-stream; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        var in = new BufferedReader(new InputStreamReader(r.body()));

        devices.remove(d1.id());
        String[] ev = frame(in, "device.revoked");
        var json = Json.parseObject(ev[1]);
        assertEquals(d1.id(), json.get("deviceId"));
        assertEquals("uuid-1", json.get("player"));
        assertEquals(ev[0], json.get("epoch") + ":" + ((Number) json.get("seq")).longValue());
        in.close();

        // the consumer was away while the second device was revoked
        devices.remove(d2.id());
        var again = new BufferedReader(new InputStreamReader(open(TOKEN, ev[0]).body()));
        String[] missed = frame(again, "device.revoked");
        assertEquals(d2.id(), Json.parseObject(missed[1]).get("deviceId"));
        again.close();

        // a consumer from another run cannot be resumed: it is told to flush
        var foreign = new BufferedReader(new InputStreamReader(open(TOKEN, "otherrun:3").body()));
        assertEquals(bus.epoch(), Json.parseObject(frame(foreign, "reset")[1]).get("epoch"));
        foreign.close();
    }
}
