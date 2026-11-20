/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebSocketFramesTest {
    static byte[] masked(int firstByte, byte[] payload) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(firstByte);
        if (payload.length < 126) o.write(0x80 | payload.length);
        else { o.write(0x80 | 126); o.write(payload.length >> 8); o.write(payload.length); }
        byte[] mask = {1, 2, 3, 4};
        o.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) o.write(payload[i] ^ mask[i & 3]);
        return o.toByteArray();
    }

    @Test
    void acceptKeyMatchesRfcExample() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WebSocketFrames.acceptKey("dGhlIHNhbXBsZSBub25jZQ=="));
    }

    @Test
    void readsMaskedTextAndReassemblesFragments() throws Exception {
        var f = WebSocketFrames.read(new ByteArrayInputStream(masked(0x81, "hello".getBytes())), 1024);
        assertEquals("hello", f.text());
        ByteArrayOutputStream two = new ByteArrayOutputStream();
        two.writeBytes(masked(0x01, "hel".getBytes()));
        two.writeBytes(masked(0x80, "lo".getBytes()));
        assertEquals("hello", WebSocketFrames.read(new ByteArrayInputStream(two.toByteArray()), 1024).text());
    }

    @Test
    void refusesUnmaskedOversizedAndReserved() {
        byte[] unmasked = {(byte) 0x81, 0x01, 'x'};
        assertThrows(WebSocketFrames.ProtocolException.class, () -> WebSocketFrames.read(new ByteArrayInputStream(unmasked), 1024));
        assertThrows(WebSocketFrames.ProtocolException.class,
                () -> WebSocketFrames.read(new ByteArrayInputStream(masked(0x81, new byte[200])), 100));
        assertThrows(WebSocketFrames.ProtocolException.class,
                () -> WebSocketFrames.read(new ByteArrayInputStream(masked(0xC1, "x".getBytes())), 100));
    }

    @Test
    void framesCompareTheirPayloadByContent() {
        var hello = new WebSocketFrames.Frame(WebSocketFrames.TEXT, "hi".getBytes(StandardCharsets.UTF_8));
        var same = new WebSocketFrames.Frame(WebSocketFrames.TEXT, "hi".getBytes(StandardCharsets.UTF_8));
        assertEquals(hello, same);
        assertEquals(hello.hashCode(), same.hashCode());
        assertNotEquals(hello, new WebSocketFrames.Frame(WebSocketFrames.TEXT, "ho".getBytes(StandardCharsets.UTF_8)));
        assertNotEquals(hello, new WebSocketFrames.Frame(WebSocketFrames.PING, "hi".getBytes(StandardCharsets.UTF_8)));
        assertFalse(hello.equals("hi"), "a different type is not equal");
        assertTrue(hello.toString().contains("payload=[104, 105]"), hello.toString());
    }

    @Test
    void writesLargeFramesWithExtendedLength() throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        WebSocketFrames.writeText(o, "a".repeat(300));
        byte[] b = o.toByteArray();
        assertEquals((byte) 0x81, b[0]);
        assertEquals(126, b[1]);
        assertEquals(300, ((b[2] & 0xFF) << 8) | (b[3] & 0xFF));
        assertEquals(304, b.length);
        assertEquals("a", new String(b, 4, 1, StandardCharsets.UTF_8));
    }
}
