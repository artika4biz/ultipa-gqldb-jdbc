package com.ultipa.jdbc;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GqlParameterRewriterTest {

    @Test
    @DisplayName("replaces each ? with a distinct named parameter, in order")
    void rewritesPlaceholdersInOrder() throws SQLException {
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(
                "MATCH (n:Person) WHERE n.name = ? AND n.age > ? RETURN n LIMIT ?");

        assertAll(
                () -> assertEquals(
                        "MATCH (n:Person) WHERE n.name = $jdbc_p1 AND n.age > $jdbc_p2 "
                                + "RETURN n LIMIT $jdbc_p3",
                        rewritten.gql()),
                () -> assertEquals(List.of("jdbc_p1", "jdbc_p2", "jdbc_p3"),
                        rewritten.parameterNames()),
                () -> assertEquals(3, rewritten.parameterCount()));
    }

    @Test
    @DisplayName("leaves a statement without placeholders untouched")
    void leavesPlainStatementsAlone() throws SQLException {
        String gql = "MATCH (n) RETURN count(n)";
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(gql);

        assertAll(
                () -> assertEquals(gql, rewritten.gql()),
                () -> assertEquals(0, rewritten.parameterCount()));
    }

    @ParameterizedTest
    @DisplayName("does not touch a ? inside any of the five GQL string-literal forms")
    @ValueSource(strings = {
            "RETURN 'is it? yes'",
            "RETURN \"is it? yes\"",
            "RETURN `is it? yes`",
            "RETURN '''is it? yes'''",
            "RETURN \"\"\"is it? yes\"\"\"",
            "RETURN ```is it? yes```",
            "RETURN @'raw ? value'",
            "RETURN 'escaped \\' and ? inside'"})
    void ignoresPlaceholdersInsideLiterals(String gql) throws SQLException {
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(gql);

        assertAll(
                () -> assertEquals(gql, rewritten.gql()),
                () -> assertEquals(0, rewritten.parameterCount()));
    }

    @ParameterizedTest
    @DisplayName("does not touch a ? inside any of the three GQL comment forms")
    @ValueSource(strings = {
            "MATCH (n) // what about ?\nRETURN n",
            "MATCH (n) -- what about ?\nRETURN n",
            "MATCH (n) /* what about ? */ RETURN n"})
    void ignoresPlaceholdersInsideComments(String gql) throws SQLException {
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(gql);

        assertAll(
                () -> assertEquals(gql, rewritten.gql()),
                () -> assertEquals(0, rewritten.parameterCount()));
    }

    @Test
    @DisplayName("rewrites real placeholders while skipping literal and commented ones")
    void mixesLiteralsCommentsAndPlaceholders() throws SQLException {
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(
                "MATCH (n) // first ? is a comment\n"
                        + "WHERE n.note = 'a ? inside' AND n.name = ? "
                        + "/* another ? */ AND n.age = ? RETURN n");

        assertAll(
                () -> assertEquals(2, rewritten.parameterCount()),
                () -> assertTrue(rewritten.gql().contains("n.name = $jdbc_p1")),
                () -> assertTrue(rewritten.gql().contains("n.age = $jdbc_p2")),
                () -> assertTrue(rewritten.gql().contains("'a ? inside'")),
                () -> assertTrue(rewritten.gql().contains("// first ? is a comment")),
                () -> assertTrue(rewritten.gql().contains("/* another ? */")));
    }

    @Test
    @DisplayName("a line comment ends at the newline, so a later ? is still a placeholder")
    void lineCommentEndsAtNewline() throws SQLException {
        GqlParameterRewriter.Rewritten rewritten =
                GqlParameterRewriter.rewrite("// ignore ?\nRETURN ?");

        assertAll(
                () -> assertEquals(1, rewritten.parameterCount()),
                () -> assertEquals("// ignore ?\nRETURN $jdbc_p1", rewritten.gql()));
    }

    @Test
    @DisplayName("a triple-quoted literal may contain newlines and single quotes")
    void handlesTripleQuotedLiterals() throws SQLException {
        String gql = "RETURN '''line one ?\nline two ' still inside?''' , ?";
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite(gql);

        assertAll(
                () -> assertEquals(1, rewritten.parameterCount()),
                () -> assertTrue(rewritten.gql().endsWith(", $jdbc_p1")),
                () -> assertTrue(rewritten.gql().contains("line one ?")));
    }

    @Test
    @DisplayName("a raw string does not honour backslash escapes")
    void rawStringsDoNotEscape() throws SQLException {
        // The backslash before the closing quote is literal, so the string ends there and the
        // final ? is a real placeholder.
        GqlParameterRewriter.Rewritten rewritten = GqlParameterRewriter.rewrite("RETURN @'a\\', ?");

        assertAll(
                () -> assertEquals(1, rewritten.parameterCount()),
                () -> assertTrue(rewritten.gql().endsWith(", $jdbc_p1")));
    }

    @ParameterizedTest
    @DisplayName("refuses an unterminated literal or comment rather than guessing positions")
    @CsvSource(delimiter = '|', value = {
            "RETURN 'unterminated ?|-quoted string",
            "RETURN \"\"\"unterminated ?|string",
            "RETURN /* unterminated ?|block comment"})
    void rejectsUnterminatedConstructs(String gql, String expectedFragment) {
        SQLException failure =
                assertThrows(SQLException.class, () -> GqlParameterRewriter.rewrite(gql));
        assertTrue(failure.getMessage().contains(expectedFragment),
                failure.getMessage() + " should mention " + expectedFragment);
    }

    @Test
    @DisplayName("rejects null GQL")
    void rejectsNull() {
        assertThrows(SQLException.class, () -> GqlParameterRewriter.rewrite(null));
    }

    @Test
    @DisplayName("counts placeholders without building the rewritten text")
    void countsParameters() throws SQLException {
        assertEquals(2, GqlParameterRewriter.countParameters("RETURN ?, ?"));
        assertEquals(0, GqlParameterRewriter.countParameters("RETURN 1"));
    }
}
