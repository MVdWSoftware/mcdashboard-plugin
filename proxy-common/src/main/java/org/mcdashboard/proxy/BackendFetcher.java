/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.proxy;

import java.io.IOException;
import java.util.Map;

/** Reads the PUBLIC placeholder values of one backend server. */
@FunctionalInterface
public interface BackendFetcher {
    /** Placeholder id to the value as text; throws when the backend cannot be read. */
    Map<String, String> fetch(BackendConfig backend) throws IOException;
}
