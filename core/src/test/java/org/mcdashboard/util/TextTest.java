/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TextTest {
    @Test
    void trimsBothEnds() {
        assertEquals("abc", Text.trim("-abc-", '-'));
        assertEquals("abc", Text.trim("abc", '-'));
        assertEquals("a-b", Text.trim("---a-b---", '-'));
        assertEquals("", Text.trim("---", '-'));
    }

    @Test
    void trimsTrailingOnly() {
        assertEquals("https://example.com", Text.trimTrailing("https://example.com///", '/'));
        assertEquals("https://example.com", Text.trimTrailing("https://example.com", '/'));
        assertEquals("", Text.trimTrailing("///", '/'));
        assertEquals("", Text.trimTrailing("", '/'));
    }
}
