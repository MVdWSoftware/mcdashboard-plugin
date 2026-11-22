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
    /**
     * Placeholder id to the value as text; throws when the backend cannot be read.
     *
     * @param backend The backend server to read from.
     * @return A map from placeholder id to its public value.
     * @throws java.io.IOException When the backend is unreachable or returns an error response.
     */
    Map<String, String> fetch(BackendConfig backend) throws IOException;
}
