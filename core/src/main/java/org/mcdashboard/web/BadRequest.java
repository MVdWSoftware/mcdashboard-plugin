/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

/** A request the client got wrong; answered with 400 and the message. */
final class BadRequest extends RuntimeException {
    BadRequest(String message) {
        super(message);
    }
}
