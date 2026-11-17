/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import com.sun.net.httpserver.HttpExchange;
import org.mcdashboard.auth.TokenService;
import org.mcdashboard.dashboard.Dashboard;
import org.mcdashboard.placeholder.PlaceholderDefinition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static org.mcdashboard.web.Exchanges.body;
import static org.mcdashboard.web.Exchanges.error;
import static org.mcdashboard.web.Exchanges.languages;
import static org.mcdashboard.web.Exchanges.respond;
import static org.mcdashboard.web.Exchanges.varyLanguage;

/** The dashboards a caller may open, translated for {@code Accept-Language}, and the developer upload. */
final class DashboardRoutes {
    /** The bundled dashboard every caller may open, even before pairing. All others need their permission. */
    private static final String PUBLIC_DASHBOARD = "player";

    private final ApiContext ctx;

    DashboardRoutes(ApiContext ctx) {
        this.ctx = ctx;
    }

    /** {@code GET /v1/dashboards}. */
    void list(HttpExchange ex, String player) throws IOException {
        List<String> preferred = languages(ex);
        List<Object> list = new ArrayList<>();
        for (Dashboard d : visible(player)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d.id());
            m.put("name", d.name().resolve(preferred, ctx.defaultLanguage()));
            if (d.description() != null) m.put("description", d.description().resolve(preferred, ctx.defaultLanguage()));
            list.add(m);
        }
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=10");
        varyLanguage(ex);
        boolean canUpload = player != null && ctx.developerMode().getAsBoolean()
                && ctx.permissions().has(player, HttpApi.DEVELOPER_PERMISSION);
        respond(ex, 200, Map.of("dashboards", list, "canUpload", canUpload));
    }

    /** {@code GET /v1/dashboards/{id}}. */
    void one(HttpExchange ex, String id, String player) throws IOException {
        Optional<Dashboard> d = visible(player).stream().filter(x -> x.id().equals(id)).findFirst();
        if (d.isEmpty()) {
            error(ex, 404, "unknown dashboard");
            return;
        }
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=10");
        varyLanguage(ex);
        respond(ex, 200, d.get().toJson(languages(ex), ctx.defaultLanguage()));
    }

    /** {@code GET /v1/dashboard}: the first dashboard the caller may open, for apps that predate presets. */
    void first(HttpExchange ex, String player) throws IOException {
        List<Dashboard> all = visible(player);
        if (all.isEmpty()) {
            error(ex, 404, "no dashboard available");
            return;
        }
        varyLanguage(ex);
        respond(ex, 200, all.get(0).toJson(languages(ex), ctx.defaultLanguage()));
    }

    /**
     * {@code POST /v1/dashboards}: saves the caller's dashboard as {@code dev-<player>.yml}, replacing their previous
     * upload, so a developer always has exactly one.
     */
    void upload(HttpExchange ex, Optional<TokenService.Claims> who) throws IOException {
        if (who.isEmpty()) {
            error(ex, 401, "authentication required");
            return;
        }
        String player = who.get().player();
        if (!ctx.developerMode().getAsBoolean()) {
            error(ex, 403, "developer mode is disabled");
            return;
        }
        if (!ctx.permissions().has(player, HttpApi.DEVELOPER_PERMISSION)) {
            error(ex, 403, "developer permission required");
            return;
        }
        Dashboard draft = DashboardUpload.parse(body(ex), ctx.placeholders());
        String clean = player.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String id = "dev-" + (clean + "000000000000").substring(0, 12);
        Dashboard saved = new Dashboard(id, draft.name(), draft.description(), 900, draft.background(), draft.backgroundImage(), draft.items());
        try {
            ctx.dashboards().save(saved, player);
        } catch (IOException e) {
            error(ex, 500, "could not save dashboard");
            return;
        }
        ctx.onDashboardsChanged().run();
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        respond(ex, 200, saved.toJson(languages(ex), ctx.defaultLanguage()));
    }

    /**
     * The dashboards this caller may open, in order: those whose permission they hold (an uploaded {@code dev-*} one
     * also through the developer permission), with only the items they may read, and only when an item is left.
     */
    private List<Dashboard> visible(String player) {
        List<Dashboard> out = new ArrayList<>();
        for (Dashboard d : ctx.dashboards().all()) {
            boolean allowed = (player == null && d.id().equals(PUBLIC_DASHBOARD))
                    || ctx.permissions().has(player, d.permission())
                    || (d.id().startsWith("dev-") && ctx.permissions().has(player, HttpApi.DEVELOPER_PERMISSION));
            if (!allowed) continue;
            Dashboard shown = readable(d, player);
            if (!shown.items().isEmpty()) out.add(shown);
        }
        out.sort(Comparator.comparingInt(Dashboard::order).thenComparing(Dashboard::id));
        return out;
    }

    /** A combined tile keeps the placeholders the caller may read; a tile with none left is dropped. */
    private Dashboard readable(Dashboard d, String player) {
        List<Dashboard.Item> items = new ArrayList<>();
        for (Dashboard.Item i : d.items()) {
            List<String> ids = new ArrayList<>();
            for (String id : i.placeholders()) {
                Optional<PlaceholderDefinition> def = ctx.placeholders().definition(id);
                if (def.isPresent() && def.get().readableBy(player, ctx.permissions())) ids.add(id);
            }
            if (!ids.isEmpty()) items.add(i.withPlaceholders(ids));
        }
        return new Dashboard(d.id(), d.name(), d.description(), d.order(), d.background(), d.backgroundImage(), items);
    }
}
