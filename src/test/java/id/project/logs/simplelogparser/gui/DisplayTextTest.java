package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.Highlighter;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.QueryCompiler;
import id.project.logs.simplelogparser.query.TextQuery;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Detail-pane text: JSON line-break and tab escapes inside strings are rendered; highlights follow. */
class DisplayTextTest {

    private static LogEntry parse(LogFormat format, String text) throws Exception {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return FormatHandler.of(format, StandardCharsets.UTF_8)
                .parse(new RawEntry(1, b.length, b, b.length, false, false), ParseContext.defaults());
    }

    private static DisplayText display(LogEntry e, Query q) throws Exception {
        CompiledQuery cq = QueryCompiler.compile(q).forFormat(LogFormat.JSON);
        MatchBudget budget = MatchBudget.unlimited();
        return DisplayText.of(e, Highlighter.ranges(e, cq, budget), cq, budget);
    }

    private static List<String> highlighted(DisplayText d) {
        return d.ranges().stream().map(r -> d.text().substring(r.start(), r.end())).toList();
    }

    @Test
    void rendersLineBreakAndTabEscapesInsideStringsOnly() {
        // JSON text: {"m":"line1\nline2\ttab\r\nnext\rlast \\n kept \" q \u00e9","k":[1, 2]}
        String raw = "{\"m\":\"line1\\nline2\\ttab\\r\\nnext\\rlast \\\\n kept \\\" q \\u00e9\",\"k\":[1, 2]}";
        String expected = "{\"m\":\"line1\nline2\ttab\nnext\nlast \\\\n kept \\\" q \\u00e9\",\"k\":[1, 2]}";
        assertThat(DisplayText.renderEscapes(raw).text()).isEqualTo(expected);
        assertThat(DisplayText.renderEscapes("{\"a\":1}").text()).isEqualTo("{\"a\":1}");
        assertThat(DisplayText.renderEscapes("{\"a\":\"x\\\\\",\"b\":\"y\\nz\"}").text())
                .as("an escaped backslash before the closing quote does not hide the next string")
                .isEqualTo("{\"a\":\"x\\\\\",\"b\":\"y\nz\"}");
    }

    @Test
    void mapKeepsHighlightsOnTheRightCharacters() throws Exception {
        LogEntry e = parse(LogFormat.JSON,
                "{\"message\":\"one\\ntwo\\tthree\",\"traceId\":\"T-1\",\"note\":\"a\\r\\nb\"}\r\n");
        DisplayText d = display(e, Query.matchAll().withFields(List.of(FieldFilter.exact("traceId", "T-1"))));
        assertThat(d.text()).isEqualTo("one\ntwo\tthree\n{\"message\":\"one\ntwo\tthree\",\"traceId\":\"T-1\",\"note\":\"a\nb\"}");
        assertThat(highlighted(d)).containsExactly("T-1");
        assertThat(d.ranges().get(0).start()).isEqualTo(d.text().indexOf("T-1"));
    }

    @Test
    void textMatchesAreHighlightedInMessageAndRenderedLine() throws Exception {
        LogEntry e = parse(LogFormat.JSON, "{\"message\":\"refund failed\\nline two\",\"x\":\"y\"}\n");
        DisplayText regex = display(e, Query.matchAll().withText(TextQuery.regex("failed\\nline")));
        assertThat(highlighted(regex)).containsExactly("failed\nline", "failed\nline");
        DisplayText literal = display(e, Query.matchAll().withText(TextQuery.literal("TWO")));
        assertThat(highlighted(literal)).containsExactly("two", "two");
    }

    @Test
    void plainEntriesAreShownAsTheirMatchText() throws Exception {
        LogEntry e = parse(LogFormat.PLAIN,
                "[a:b:k: v] 2026-10-18 00:00:00.000 [t] INFO c - payload {\"m\":\"x\\ny\"}\r\nnext line\r\n");
        DisplayText d = DisplayText.of(e, List.of(new Highlighter.Range(0, 3)), null, MatchBudget.unlimited());
        assertThat(d.text()).isEqualTo(e.matchText()).contains("x\\ny");
        assertThat(d.ranges()).containsExactly(new Highlighter.Range(0, 3));
    }
}
