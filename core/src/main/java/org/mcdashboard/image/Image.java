/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

import java.util.Arrays;

/** A validated image and the time it was fetched or last modified (epoch milliseconds).
 *
 * @param bytes The raw image bytes, already validated by {@link ImageFormat#sniff}.
 * @param timeMs Epoch milliseconds when the image was obtained or last updated.
 */
public record Image(byte[] bytes, long timeMs) {
    @Override
    public boolean equals(Object o) {
        return o instanceof Image i && timeMs == i.timeMs && Arrays.equals(bytes, i.bytes);
    }

    @Override
    public int hashCode() {
        return 31 * Long.hashCode(timeMs) + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
        return "Image[bytes=" + Arrays.toString(bytes) + ", timeMs=" + timeMs + "]";
    }
}
