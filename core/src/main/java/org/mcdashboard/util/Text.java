/*
 * Copyright (c) 2026 MVdW-Software
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.mcdashboard.util;

/** Trimming that a backtracking regular expression would do in super-linear time. */
public final class Text {
    private Text() {
    }

    /** The text without leading and trailing occurrences of {@code c}.
     *
     * @param s The text to trim.
     * @param c The character to remove from both ends.
     * @return The trimmed text.
     */
    public static String trim(String s, char c) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == c) start++;
        while (start < end && s.charAt(end - 1) == c) end--;
        return s.substring(start, end);
    }

    /** The text without trailing occurrences of {@code c}.
     *
     * @param s The text to trim.
     * @param c The character to remove from the end.
     * @return The trimmed text.
     */
    public static String trimTrailing(String s, char c) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == c) end--;
        return s.substring(0, end);
    }
}
