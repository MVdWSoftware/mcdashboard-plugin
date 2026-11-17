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
    /** One recorded value of a placeholder. */
    public record Sample(String id, long timeMs, String value) {
    }

    /** A stored value: exactly one of {@code number} and {@code text} is set. */
    public record Result(Double number, String text) {
        /** The string a reader sees: the number in plain notation, or the text. */
        public String asString() {
            return number != null ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() : text;
        }
    }

    public static final int MAX_TEXT = 255;
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+([.,]\\d+)?");

    private Samples() {
    }

    /** Splits a raw placeholder value into its stored form. */
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
