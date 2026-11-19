/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.web;

import org.mcdashboard.placeholder.PlaceholderDefinition;
import org.mcdashboard.placeholder.PlaceholderService;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Encodes values as SenML (RFC 8428, {@code application/senml+json}), the IETF standard for
 * sensor measurements, so any SenML-aware client, proxy or collector can consume the data.
 * Each record: {@code n} name (placeholder id), {@code t} time (unix seconds), {@code u} unit and
 * one of {@code v} (number), {@code vb} (boolean), {@code vs} (string) or {@code vd} (data: an IMAGE as base64url
 * without padding; the type is sniffed from the bytes; or, with {@code encode: false}, the https URL to load in {@code vs}).
 * A LIST is a table as compact JSON in {@code vs}.
 */
public final class SenMl {
    /** Media type of a SenML JSON pack. */
    public static final String MEDIA_TYPE = "application/senml+json";

    private SenMl() {
    }

    /**
     * PlaceholderAPI values often carry decoration: legacy colour codes, a leading {@code *} (the
     * server expansion marks a capped TPS that way) or thousands separators. Numbers must survive that.
     */
    static String clean(String raw) {
        return raw.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
    }

    private static String numeric(String cleaned) {
        return cleaned.replaceFirst("^[*~<>≈ ]+", "").replace(" ", "").replace(",", ".").replace("%", "");
    }

    private static final Pattern INT = Pattern.compile("[*~<>≈]? ?[-+]?\\d+%?");
    private static final Pattern DEC = Pattern.compile("[*~<>≈]? ?[-+]?\\d+[.,]\\d+%?");

    /** AUTO: integer becomes {@code v}, decimal {@code v}, true/false {@code vb}, anything else {@code vs}. */
    private static void infer(Map<String, Object> r, String raw) {
        try {
            if (INT.matcher(raw).matches()) {
                String n = numeric(raw);
                if (!n.matches("[-+]?0\\d+")) { // "007" is an identifier, not a number
                    r.put("v", Long.parseLong(n.startsWith("+") ? n.substring(1) : n));
                    return;
                }
            } else if (DEC.matcher(raw).matches()) {
                r.put("v", Double.parseDouble(numeric(raw)));
                return;
            } else if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) {
                r.put("vb", Boolean.parseBoolean(raw));
                return;
            }
        } catch (NumberFormatException e) {
            // too large for a long: fall through to a string
        }
        r.put("vs", raw);
    }

    /** Builds one SenML record for a resolved value. */
    public static Map<String, Object> record(PlaceholderDefinition def, PlaceholderService.Value value) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("n", def.id());
        r.put("t", value.updatedMs() / 1000.0);
        if (def.unit() != null) r.put("u", def.unit());
        if (def.type() == PlaceholderDefinition.Type.IMAGE) {
            r.put(def.encode() ? "vd" : "vs", value.value()); // vs: an https URL the app loads itself (encode: false)
            return r;
        }
        if (def.type() == PlaceholderDefinition.Type.LIST) {
            r.put("vs", value.value()); // compact JSON {"columns":[...],"rows":[[...]]}, colour codes kept
            return r;
        }
        String raw = clean(value.value());
        if (def.type() == PlaceholderDefinition.Type.AUTO) {
            infer(r, raw);
            return r;
        }
        try {
            switch (def.type()) {
                case INTEGER -> r.put("v", Long.parseLong(numeric(raw).replaceAll("\\.0+$", "")));
                case DECIMAL, PERCENTAGE -> r.put("v", Double.parseDouble(numeric(raw)));
                case BOOLEAN -> {
                    if (raw.equalsIgnoreCase("true") || raw.equalsIgnoreCase("false")) r.put("vb", Boolean.parseBoolean(raw));
                    else r.put("vs", raw);
                }
                default -> r.put("vs", raw);
            }
        } catch (NumberFormatException e) {
            r.put("vs", raw); // not numeric after all (e.g. a placeholder that failed to resolve)
        }
        return r;
    }
}
