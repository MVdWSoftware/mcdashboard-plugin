/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.commands;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;

/** The clickable line a {@link DashboardCommands.Action} becomes in chat: links open in the browser, commands run. */
final class ChatActions {
    private ChatActions() {
    }

    static TextComponent line(DashboardCommands.Action action) {
        boolean link = action.url() != null;
        TextComponent line = new TextComponent(" §8» ");
        TextComponent button = new TextComponent("[" + action.label() + "]");
        button.setColor(link ? ChatColor.AQUA : ChatColor.GREEN);
        button.setUnderlined(true);
        button.setClickEvent(new ClickEvent(link ? ClickEvent.Action.OPEN_URL : ClickEvent.Action.RUN_COMMAND,
                link ? action.url() : action.command()));
        button.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(action.hover())));
        line.addExtra(button);
        return line;
    }
}
