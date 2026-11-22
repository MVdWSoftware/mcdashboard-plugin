/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.history;

import org.mcdashboard.placeholder.HistoryService.Point;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * How a sampled value is kept: a number becomes the {@code sosa:hasSimpleResult} number, anything else the text.
 * Decoration that SenML ignores (colour codes, a leading {@code *}, a trailing {@code %}) is dropped from numbers.
 */
public final class Samples {
    /** One recorded value of a placeholder.
     *
     * @param id     The placeholder identifier that produced this sample.
     * @param timeMs The timestamp when the sample was taken.
     * @param value  The raw placeholder value string.
     */
    public record Sample(String id, long timeMs, String value) {
    }

    /** A stored value: exactly one of {@code number} and {@code text} is set.
     *
     * @param number Numeric value when the sample represents a number.
     * @param text   Text value when the sample is not numeric.
     */
    public record Result(Double number, String text) {
        /** The string a reader sees: the number in plain notation, or the text.
         *
         * @return The formatted numeric string, or the original text value.
         */
        public String asString() {
            return number != null ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() : text;
        }
    }

    /** Maximum length for a text sample stored in the observation table. */
    public static final int MAX_TEXT = 255;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+([.,]\\d+)?");

    private Samples() {
    }

    /** Splits a raw placeholder value into its stored form.
     *
     * @param raw The original placeholder value string, possibly with decoration codes.
     * @return A Result containing either the parsed numeric value or the cleaned text.
     */
    public static Result encode(String raw) {
        String cleaned = raw.replaceAll("[§&][0-9a-fk-orA-FK-OR]", "").trim();
        String n = cleaned.replaceFirst("^[*~<>≈ ]+", "").replace(" ", "").replace("%", "");
        if (NUMBER.matcher(n).matches() && !n.matches("[-+]?0\\d+")) { // "007" is an identifier, not a number
            try {
                double d = Double.parseDouble(n.replace(',', '.'));
                if (!Double.isNaN(d) && !Double.isInfinite(d)) return new Result(d, null);
            } catch (NumberFormatException e) {
                // fall through: keep it as text
            }
        }
        return new Result(null, cleaned.length() > MAX_TEXT ? cleaned.substring(0, MAX_TEXT) : cleaned);
    }

    /**
     * Reduces a series to at most {@code points} by averaging the numbers in equal time buckets (text: the last value
     * of the bucket). Each bucket keeps the time of its last sample. Series that are short enough pass through as is.
     *
     * @param in     The input list of data points, sorted by timestamp.
     * @param points Maximum number of output points desired.
     * @return A new list with at most {@code points} elements after reduction.
     */
    public static List<Point> reduce(List<Point> in, int points) {
        if (points < 1 || in.size() <= points) return in;
        long first = in.get(0).timeMs();
        long last = in.get(in.size() - 1).timeMs();
        long bucket = Math.max(1, (last - first) / points + 1);
        List<Point> out = new ArrayList<>();
        long current = -1;
        double sum = 0;
        int count = 0;
        long time = 0;
        String text = null;
        for (Point p : in) {
            long b = (p.timeMs() - first) / bucket;
            if (b != current && current >= 0) {
                out.add(new Point(time, count > 0 ? new Result(sum / count, null).asString() : text));
                sum = 0;
                count = 0;
                text = null;
            }
            current = b;
            time = p.timeMs();
            Result r = encode(p.value());
            if (r.number() != null) {
                sum += r.number();
                count++;
            } else {
                text = r.text();
            }
        }
        out.add(new Point(time, count > 0 ? new Result(sum / count, null).asString() : text));
        return out;
    }
}
