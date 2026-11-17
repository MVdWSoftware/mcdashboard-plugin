package org.mcdashboard.image;

/** A validated image and the time it was fetched or last modified (epoch milliseconds). */
public record Image(byte[] bytes, long timeMs) {
}
