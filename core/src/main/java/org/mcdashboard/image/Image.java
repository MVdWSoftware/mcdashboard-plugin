/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.image;

/** A validated image and the time it was fetched or last modified (epoch milliseconds). */
public record Image(byte[] bytes, long timeMs) {
}
