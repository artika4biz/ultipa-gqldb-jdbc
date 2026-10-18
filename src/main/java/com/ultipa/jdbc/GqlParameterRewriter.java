package com.ultipa.jdbc;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the JDBC {@code ?} placeholders of a {@link java.sql.PreparedStatement} into GQLDB's
 * native named parameters.
 *
 * <p>GQLDB binds parameters <em>server side</em>: the wire protocol carries a
 * {@code repeated Parameter} list of typed values alongside the query text, and a query refers
 * to one as {@code $name}. JDBC, by contrast, uses positional {@code ?}. This class bridges the
 * two by replacing the <em>n</em>th {@code ?} with {@code $jdbc_pn} and reporting the names it
 * produced, so {@link UltipaPreparedStatement} can bind values by position onto names.
 *
 * <p><strong>Values are never interpolated into the query text.</strong> Only the placeholders
 * are rewritten; the bound values travel as typed protocol values and are never serialised,
 * escaped or quoted by this driver. There is consequently no injection surface in parameter
 * binding.
 *
 * <h2>What the scanner skips</h2>
 *
 * <p>A {@code ?} is only a placeholder outside literals and comments, so the scanner recognises
 * every lexical form Ultipa GQL documents:
 *
 * <ul>
 *   <li>single-quoted {@code '...'}, double-quoted {@code "..."} and backtick-quoted
 *       {@code `...`} strings, in which {@code \} escapes the next character;</li>
 *   <li>triple-quoted strings {@code '''...'''}, {@code """..."""} and
 *       <code>```...```</code>, which may contain newlines and single embedded quotes;</li>
 *   <li>raw strings introduced by {@code @}, such as {@code @'...'}, in which {@code \} does not
 *       escape;</li>
 *   <li>line comments starting with {@code //} or {@code --}, which GQL both support;</li>
 *   <li>block comments {@code /* ... *}{@code /}.</li>
 * </ul>
 *
 * <h2>Documented limitation</h2>
 *
 * <p>The scanner is lexical, not a GQL parser. Any {@code ?} that reaches it outside a literal
 * or comment is treated as a placeholder. If a future GQL construct gives {@code ?} a meaning of
 * its own in that position, such a query must be sent through a plain
 * {@link java.sql.Statement}, which passes text through untouched.
 *
 * <p>Generated names use the reserved {@code jdbc_p} prefix. A query that mixes {@code ?}
 * placeholders with its own {@code $jdbc_pN} parameters would collide, so do not name your own
 * parameters that way.
 */
public final class GqlParameterRewriter {

    /** The prefix of every generated parameter name. */
    static final String PARAMETER_PREFIX = "jdbc_p";

    private GqlParameterRewriter() {
    }

    /**
     * The outcome of rewriting a statement.
     *
     * @param gql            the query text with {@code ?} replaced by {@code $jdbc_pN}
     * @param parameterNames the generated names, in placeholder order; index 0 corresponds to
     *                       JDBC parameter 1
     */
    public record Rewritten(String gql, List<String> parameterNames) {

        /**
         * Creates a result, defensively copying the names.
         *
         * @param gql            the rewritten query text
         * @param parameterNames the generated names in placeholder order
         */
        public Rewritten(String gql, List<String> parameterNames) {
            this.gql = gql;
            this.parameterNames = List.copyOf(parameterNames);
        }

        /** Returns how many placeholders the statement has. */
        public int parameterCount() {
            return parameterNames.size();
        }
    }

    /**
     * Rewrites the placeholders of one statement.
     *
     * @param gql the GQL text as the caller wrote it
     * @return the rewritten text and the generated parameter names
     * @throws SQLException if {@code gql} is {@code null}, or if a literal or block comment is
     *                      left unterminated, which would make the placeholder positions
     *                      meaningless
     */
    public static Rewritten rewrite(String gql) throws SQLException {
        if (gql == null) {
            throw UltipaExceptionMapper.invalidArgument("GQL text must not be null");
        }

        StringBuilder out = new StringBuilder(gql.length() + 16);
        List<String> names = new ArrayList<>();
        int length = gql.length();
        int index = 0;

        while (index < length) {
            char current = gql.charAt(index);

            // Comments.
            if (current == '/' && index + 1 < length && gql.charAt(index + 1) == '/') {
                index = copyLineComment(gql, index, out);
                continue;
            }
            if (current == '-' && index + 1 < length && gql.charAt(index + 1) == '-') {
                index = copyLineComment(gql, index, out);
                continue;
            }
            if (current == '/' && index + 1 < length && gql.charAt(index + 1) == '*') {
                index = copyBlockComment(gql, index, out);
                continue;
            }

            // Raw strings: @'...' and the same with the other two quote characters.
            if (current == '@' && index + 1 < length && isQuote(gql.charAt(index + 1))) {
                out.append(current);
                index = copyQuoted(gql, index + 1, out, false);
                continue;
            }

            // Quoted strings, triple-quoted first so that ''' is not read as an empty ''.
            if (isQuote(current)) {
                if (isTripleQuoteAt(gql, index, current)) {
                    index = copyTripleQuoted(gql, index, current, out);
                } else {
                    index = copyQuoted(gql, index, out, true);
                }
                continue;
            }

            if (current == '?') {
                String name = PARAMETER_PREFIX + (names.size() + 1);
                names.add(name);
                out.append('$').append(name);
                index++;
                continue;
            }

            out.append(current);
            index++;
        }

        return new Rewritten(out.toString(), names);
    }

    /**
     * Returns how many {@code ?} placeholders a statement has, without building the rewritten
     * text.
     *
     * @param gql the GQL text
     * @return the placeholder count
     * @throws SQLException if the text cannot be scanned
     */
    public static int countParameters(String gql) throws SQLException {
        return rewrite(gql).parameterCount();
    }

    private static boolean isQuote(char character) {
        return character == '\'' || character == '"' || character == '`';
    }

    private static boolean isTripleQuoteAt(String gql, int index, char quote) {
        return index + 2 < gql.length()
                && gql.charAt(index + 1) == quote
                && gql.charAt(index + 2) == quote;
    }

    /** Copies a {@code //} or {@code --} comment up to, but not including, the line break. */
    private static int copyLineComment(String gql, int start, StringBuilder out) {
        int index = start;
        while (index < gql.length()) {
            char current = gql.charAt(index);
            if (current == '\n' || current == '\r') {
                break;
            }
            out.append(current);
            index++;
        }
        return index;
    }

    private static int copyBlockComment(String gql, int start, StringBuilder out) throws SQLException {
        out.append('/').append('*');
        int index = start + 2;
        while (index < gql.length()) {
            if (gql.charAt(index) == '*' && index + 1 < gql.length() && gql.charAt(index + 1) == '/') {
                out.append('*').append('/');
                return index + 2;
            }
            out.append(gql.charAt(index));
            index++;
        }
        throw UltipaExceptionMapper.invalidArgument(
                "GQL text has an unterminated block comment, so parameter positions cannot be determined");
    }

    /**
     * Copies a single-delimiter string literal, including its delimiters.
     *
     * @param start        the index of the opening delimiter
     * @param honourEscape whether {@code \} escapes the next character; {@code false} for raw
     *                     strings
     * @return the index just past the closing delimiter
     */
    private static int copyQuoted(String gql, int start, StringBuilder out, boolean honourEscape)
            throws SQLException {
        char quote = gql.charAt(start);
        out.append(quote);
        int index = start + 1;
        while (index < gql.length()) {
            char current = gql.charAt(index);
            if (honourEscape && current == '\\' && index + 1 < gql.length()) {
                out.append(current).append(gql.charAt(index + 1));
                index += 2;
                continue;
            }
            out.append(current);
            index++;
            if (current == quote) {
                return index;
            }
        }
        throw UltipaExceptionMapper.invalidArgument(
                "GQL text has an unterminated " + quote + "-quoted string, so parameter positions "
                        + "cannot be determined");
    }

    /** Copies a triple-quoted literal, including both triple delimiters. */
    private static int copyTripleQuoted(String gql, int start, char quote, StringBuilder out)
            throws SQLException {
        out.append(quote).append(quote).append(quote);
        int index = start + 3;
        while (index < gql.length()) {
            if (gql.charAt(index) == quote
                    && index + 2 < gql.length()
                    && gql.charAt(index + 1) == quote
                    && gql.charAt(index + 2) == quote) {
                out.append(quote).append(quote).append(quote);
                return index + 3;
            }
            out.append(gql.charAt(index));
            index++;
        }
        throw UltipaExceptionMapper.invalidArgument(
                "GQL text has an unterminated triple-" + quote + " string, so parameter positions "
                        + "cannot be determined");
    }
}
