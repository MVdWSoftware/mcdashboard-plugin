/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.platform;

/** A repeating task handed out by the platform; cancel it to stop it for good (a reload must not leave it running). */
public interface ScheduledTask {
    /** Stops the task; safe to call more than once. */
    void cancel();
}
