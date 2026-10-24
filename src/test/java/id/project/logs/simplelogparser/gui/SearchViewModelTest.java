package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchMode;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.TextQuery;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** The GUI's query inputs, without a running FX toolkit (TRD 9.7). */
class SearchViewModelTest {

    @Test
    void producesTheSameQueryAsTheEquivalentCliOptions() {
        // slp --field merchantId=786 --field shiftId=147581 --field shiftId=147582 --text ORDERID=2837537
        //     --in request --level error,warn --from 2026-10-18T07:00 --to 2026-10-18T08:00
        SearchViewModel vm = new SearchViewModel();
        vm.fieldRows().get(0).fieldProperty().set("merchantId");
        vm.fieldRows().get(0).valuesProperty().set("786");
        SearchViewModel.FieldRow shift = vm.addFieldRow();
        shift.fieldProperty().set(" shiftId ");
        shift.valuesProperty().set("147581, 147582,");
        vm.textProperty().set("ORDERID=2837537");
        vm.scopeProperty().set(Scope.REQUEST);
        vm.levelProperty(Level.ERROR).set(true);
        vm.levelProperty(Level.WARN).set(true);
        vm.fromProperty().set("2026-10-18T07:00");
        vm.toProperty().set("2026-10-18 08:00");

        Query expected = new Query(
                List.of(FieldFilter.exact("merchantId", "786"), FieldFilter.exact("shiftId", "147581", "147582")),
                Optional.of(new TextQuery("ORDERID=2837537", false, false, false, Scope.REQUEST)),
                Set.of(Level.ERROR, Level.WARN),
                Optional.of(Instant.parse("2026-10-18T00:00:00Z")),
                Optional.of(Instant.parse("2026-10-18T01:00:00Z")));
        assertThat(vm.toQuery()).isEqualTo(expected);
        assertThat(vm.compile().ok()).isTrue();
        assertThat(vm.validationMessageProperty().get()).isEmpty();
    }

    @Test
    void caseToggleAndRegexOptionsMapOneToOne() {
        SearchViewModel vm = new SearchViewModel();
        vm.textProperty().set("\"status\":\\s*5\\d\\d");
        vm.regexProperty().set(true);
        vm.dotAllProperty().set(true);
        assertThat(vm.toQuery().text()).contains(new TextQuery("\"status\":\\s*5\\d\\d", true, false, true, Scope.ALL));
        vm.caseSensitiveProperty().set(true);
        assertThat(vm.toQuery().text().get().caseSensitive()).isTrue();
        vm.regexProperty().set(false);
        assertThat(vm.toQuery().text().get().dotAll()).as("DOTALL only applies to regex").isFalse();
        vm.fieldRows().get(0).fieldProperty().set("requestId");
        vm.fieldRows().get(0).modeProperty().set(MatchMode.REGEX);
        vm.fieldRows().get(0).valuesProperty().set("^Kw.*");
        assertThat(vm.toQuery().fields()).containsExactly(new FieldFilter("requestId", MatchMode.REGEX, List.of("^Kw.*")));
    }

    @Test
    void invalidInputIsReportedInlineBeforeAnyScan() {
        SearchViewModel vm = new SearchViewModel();
        vm.textProperty().set("status\":(5");
        vm.regexProperty().set(true);
        assertThat(vm.compile().ok()).isFalse();
        assertThat(vm.validationMessageProperty().get()).startsWith("Invalid regex").contains("position");

        vm.regexProperty().set(false);
        assertThat(vm.validationMessageProperty().get()).as("same text is a valid literal").isEmpty();

        vm.fromProperty().set("yesterday");
        assertThat(vm.validationMessageProperty().get()).contains("invalid time");
        vm.fromProperty().set("2026-10-18T08:00");
        vm.toProperty().set("2026-10-18T07:00");
        assertThat(vm.validationMessageProperty().get()).contains("before");
        vm.toProperty().set("");

        vm.fieldRows().get(0).valuesProperty().set("786");
        assertThat(vm.validationMessageProperty().get()).contains("Field name is missing");
        vm.fieldRows().get(0).fieldProperty().set("merchantId");
        vm.fieldRows().get(0).valuesProperty().set(" , ");
        assertThat(vm.validationMessageProperty().get()).contains("at least one value");
        vm.fieldRows().get(0).valuesProperty().set("");
        vm.fieldRows().get(0).fieldProperty().set("");
        assertThat(vm.validationMessageProperty().get()).isEmpty();
        vm.textProperty().set("");
        assertThat(vm.toQuery()).isEqualTo(Query.matchAll().withRange(Instant.parse("2026-10-18T01:00:00Z"), null));
    }

    @Test
    void scopeIsDisabledAndIgnoredForJson() {
        SearchViewModel vm = new SearchViewModel();
        vm.textProperty().set("slow");
        vm.scopeProperty().set(Scope.RESPONSE);
        assertThat(vm.scopeDisabledProperty().get()).isFalse();
        assertThat(vm.toQuery().text().get().scope()).isEqualTo(Scope.RESPONSE);
        vm.formatProperty().set(LogFormat.JSON);
        assertThat(vm.scopeDisabledProperty().get()).isTrue();
        assertThat(vm.toQuery().text().get().scope()).isEqualTo(Scope.ALL);
        assertThat(vm.compile().query().scope()).isEqualTo(Scope.ALL);
    }

    @Test
    void emptyInputsMatchEverything() {
        assertThat(new SearchViewModel().toQuery()).isEqualTo(Query.matchAll());
    }
}
