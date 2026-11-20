/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.junit.jupiter.api.Test;

class ChatActionsTest {
    @Test
    void linksOpenTheBrowserAndCommandsRun() {
        TextComponent store = (TextComponent) ChatActions.line(DashboardCommands.Action.link("Google Play", "https://example.org/app", "Open it")).getExtra().get(0);
        assertEquals("[Google Play]", store.getText());
        assertEquals(ChatColor.AQUA, store.getColor());
        assertEquals(ClickEvent.Action.OPEN_URL, store.getClickEvent().getAction());
        assertEquals("https://example.org/app", store.getClickEvent().getValue());

        TextComponent link = (TextComponent) ChatActions.line(DashboardCommands.Action.command("Get a pairing code", "/mcd link", "Run it")).getExtra().get(0);
        assertEquals(ChatColor.GREEN, link.getColor());
        assertEquals(ClickEvent.Action.RUN_COMMAND, link.getClickEvent().getAction());
        assertEquals("/mcd link", link.getClickEvent().getValue());
    }
}
