package com.ultipa.jdbc;

import com.gqldb.Row;
import com.gqldb.types.TypedValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for building the result sets {@link UltipaDatabaseMetaData} returns, and for matching
 * the pattern arguments it accepts.
 *
 * <p>Metadata result sets are assembled locally from the server's introspection output, so they
 * are built from the same {@link Row} and {@link TypedValue} types as a query result and read
 * through the ordinary {@link UltipaResultSet}. That keeps one code path for value conversion
 * and column lookup.
 */
final class UltipaMetaDataResultSets {

    private UltipaMetaDataResultSets() {
    }

    /**
     * Builds a result set from column labels and plain Java values.
     *
     * @param columnLabels the column labels
     * @param rows         the rows; each must have one value per column, and may contain
     *                     {@code null}, {@link String} or {@link Integer} values
     * @return a read-only result set with no owning statement
     */
    static UltipaResultSet of(List<String> columnLabels, List<List<Object>> rows) {
        List<Row> encoded = new ArrayList<>(rows.size());
        for (List<Object> values : rows) {
            List<TypedValue> typed = new ArrayList<>(values.size());
            for (Object value : values) {
                typed.add(TypedValue.fromValue(value));
            }
            Row row = new Row(typed);
            encoded.add(row);
        }
        return new UltipaResultSet(null, columnLabels, encoded);
    }

    /** Builds an empty result set with the given column labels. */
    static UltipaResultSet empty(List<String> columnLabels) {
        return of(columnLabels, List.of());
    }

    /**
     * Matches a name against a JDBC metadata pattern.
     *
     * <p>JDBC patterns use {@code %} for any sequence and {@code _} for any single character,
     * with {@code \} as the escape — which is why
     * {@link UltipaDatabaseMetaData#getSearchStringEscape()} reports {@code \}. A {@code null}
     * pattern means "no constraint", as the contract specifies. Matching is case-insensitive,
     * so a tool that upper-cases its arguments still finds a label.
     *
     * @param pattern the pattern, or {@code null} for no constraint
     * @param name    the name to test, may be {@code null}
     * @return whether the name matches
     */
    static boolean matches(String pattern, String name) {
        if (pattern == null) {
            return true;
        }
        if (name == null) {
            return false;
        }
        return toRegex(pattern).matcher(name).matches();
    }

    private static Pattern toRegex(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            char current = pattern.charAt(i);
            switch (current) {
                case '%':
                    regex.append(".*");
                    break;
                case '_':
                    regex.append('.');
                    break;
                case '\\':
                    if (i + 1 < pattern.length()) {
                        regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
                    } else {
                        regex.append(Matcher.quoteReplacement("\\"));
                    }
                    break;
                default:
                    regex.append(Pattern.quote(String.valueOf(current)));
                    break;
            }
        }
        return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Matches an exact name argument, where JDBC treats {@code null} as "no constraint" and the
     * value as a literal rather than a pattern.
     *
     * @param expected the required name, or {@code null} for no constraint
     * @param actual   the name to test
     * @return whether the name matches
     */
    static boolean equalsIgnoringCase(String expected, String actual) {
        if (expected == null) {
            return true;
        }
        return actual != null && expected.toLowerCase(Locale.ROOT).equals(actual.toLowerCase(Locale.ROOT));
    }
}
