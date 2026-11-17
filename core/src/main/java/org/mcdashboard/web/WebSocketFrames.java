/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The subset of RFC 6455 the live feed needs: handshake accept key, text/ping/pong/close frames. */
public final class WebSocketFrames {
    public static final int TEXT = 0x1;
    public static final int CLOSE = 0x8;
    public static final int PING = 0x9;
    public static final int PONG = 0xA;
    private static final int CONTINUATION = 0x0;
    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    /** A complete (reassembled) message or control frame. */
    public record Frame(int opcode, byte[] payload) {
        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    /** Thrown for protocol violations; the connection must be closed. */
    public static final class ProtocolException extends IOException {
        public ProtocolException(String message) {
            super(message);
        }
    }

    private WebSocketFrames() {
    }

    /** Computes {@code Sec-WebSocket-Accept} for a client key. */
    public static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            return Base64.getEncoder().encodeToString(sha1.digest((clientKey + GUID).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Reads one message. Client frames must be masked; fragmented text messages are reassembled;
     * anything larger than {@code maxPayload} is refused rather than buffered.
     */
    public static Frame read(InputStream in, int maxPayload) throws IOException {
        DataInputStream data = new DataInputStream(in);
        ByteArrayOutputStream message = null;
        int messageOpcode = -1;
        while (true) {
            int b0 = data.read();
            if (b0 < 0) throw new EOFException();
            int b1 = data.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            if ((b0 & 0x70) != 0) throw new ProtocolException("reserved bits set");
            int opcode = b0 & 0x0F;
            if ((b1 & 0x80) == 0) throw new ProtocolException("client frame not masked");
            long len = b1 & 0x7F;
            if (len == 126) len = data.readUnsignedShort();
            else if (len == 127) len = data.readLong();
            boolean control = (opcode & 0x8) != 0;
            if (control && (len > 125 || !fin)) throw new ProtocolException("bad control frame");
            if (len < 0 || len > maxPayload) throw new ProtocolException("frame too large");
            byte[] mask = new byte[4];
            data.readFully(mask);
            byte[] payload = new byte[(int) len];
            data.readFully(payload);
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];

            if (control) return new Frame(opcode, payload);
            if (opcode == CONTINUATION) {
                if (message == null) throw new ProtocolException("unexpected continuation");
            } else if (opcode == TEXT) {
                if (message != null) throw new ProtocolException("interleaved message");
                message = new ByteArrayOutputStream();
                messageOpcode = opcode;
            } else {
                throw new ProtocolException("unsupported opcode " + opcode);
            }
            if (message.size() + payload.length > maxPayload) throw new ProtocolException("message too large");
            message.write(payload);
            if (fin) return new Frame(messageOpcode, message.toByteArray());
        }
    }

    /** Writes one unmasked server frame. */
    public static void write(OutputStream out, int opcode, byte[] payload) throws IOException {
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 10);
        f.write(0x80 | opcode);
        if (payload.length < 126) {
            f.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            f.write(126);
            f.write(payload.length >> 8);
            f.write(payload.length);
        } else {
            f.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) f.write((int) ((long) payload.length >> shift));
        }
        f.write(payload);
        out.write(f.toByteArray());
        out.flush();
    }

    /** Writes a text frame. */
    public static void writeText(OutputStream out, String text) throws IOException {
        write(out, TEXT, text.getBytes(StandardCharsets.UTF_8));
    }
}
