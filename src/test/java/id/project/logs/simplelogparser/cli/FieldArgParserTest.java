package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldArgParserTest {

    @Test
    void parsesModesAndMergesRepeatedKeys() {
        assertThat(FieldArgParser.parse(List.of("shiftId=1", "merchantId*=78", "shiftId=2", "requestId~=^a.*=$",
                "shiftId*=3", "url=http://x?a=b")))
                .containsExactly(
                        new FieldFilter("shiftId", MatchMode.EXACT, List.of("1", "2")),
                        new FieldFilter("merchantId", MatchMode.CONTAINS, List.of("78")),
                        new FieldFilter("requestId", MatchMode.REGEX, List.of("^a.*=$")),
                        new FieldFilter("shiftId", MatchMode.CONTAINS, List.of("3")),
                        new FieldFilter("url", MatchMode.EXACT, List.of("http://x?a=b")));
    }

    @Test
    void rejectsMissingKeyOrSeparator() {
        assertThatThrownBy(() -> FieldArgParser.parse(List.of("novalue"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldArgParser.parse(List.of("=x"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldArgParser.parse(List.of("*=x"))).isInstanceOf(IllegalArgumentException.class);
        assertThat(FieldArgParser.parse(List.of("k="))).containsExactly(FieldFilter.exact("k", ""));
    }
}
