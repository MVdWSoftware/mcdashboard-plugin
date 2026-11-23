/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.qr;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The picture that the Spigot module draws, checked as arithmetic so it needs no server. */
class QrMapLayoutTest {
    @Test
    void aSmallCodeUsesTheLargestModules() {
        QrMapLayout layout = new QrMapLayout(21); // version 1: 21 + 8 quiet modules at 4 pixels
        assertEquals(4, layout.scale());
        assertEquals(6, layout.offset());
        assertEquals(20, layout.logoSize()); // 116 drawn pixels would give 21, capped at the maximum
        assertEquals(54, layout.logoStart());
    }

    @Test
    void aLargeCodeFallsBackToOnePixelPerModuleAndASmallerLogo() {
        QrMapLayout layout = new QrMapLayout(57); // version 10: 57 + 8 quiet modules do not fit at 2 pixels
        assertEquals(1, layout.scale());
        assertEquals(31, layout.offset());
        assertEquals(12, layout.logoSize()); // 65 drawn pixels: the logo shrinks with the code
        assertEquals(58, layout.logoStart());
    }

    @Test
    void modulesAreCentred() {
        QrMapLayout layout = new QrMapLayout(49); // version 8: the common pairing link
        assertEquals(2, layout.scale());
        assertEquals(7, layout.offset());
        assertEquals(20, layout.logoSize());
    }

    @Test
    void darkFollowsTheModuleOfThePixel() {
        QrCode qr = QrCode.encode("a");
        QrMapLayout layout = new QrMapLayout(qr.size());
        int scale = layout.scale();
        int first = layout.offset() + QrMapLayout.QUIET_MODULES * scale;
        assertTrue(qr.dark(0, 0)); // finder corner
        assertTrue(layout.dark(qr, first, first));
        assertTrue(layout.dark(qr, first + scale - 1, first + scale - 1));
        assertFalse(layout.dark(qr, first - 1, first)); // quiet zone
        assertFalse(layout.dark(qr, 0, 0));
    }

    @Test
    void theLogoAreaIncludesItsBorder() {
        QrMapLayout layout = new QrMapLayout(57);
        int start = layout.logoStart();
        assertTrue(layout.logo(start, start));
        assertTrue(layout.logo(start - QrMapLayout.LOGO_MARGIN, start - QrMapLayout.LOGO_MARGIN));
        assertFalse(layout.logo(start - QrMapLayout.LOGO_MARGIN - 1, start));
        assertFalse(layout.logo(start + layout.logoSize() + QrMapLayout.LOGO_MARGIN, start));
        assertFalse(layout.logo(0, 0));
    }
}
