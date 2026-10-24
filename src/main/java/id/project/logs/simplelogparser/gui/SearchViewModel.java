package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchMode;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.QueryCompiler;
import id.project.logs.simplelogparser.query.QuerySyntaxException;
import id.project.logs.simplelogparser.query.TextQuery;
import id.project.logs.simplelogparser.query.TimeParser;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query inputs of one tab, as JavaFX properties without any controls. {@link #compile()} validates and
 * compiles them before any scan (AC7); {@link #validationMessageProperty()} is kept up to date for inline
 * display. The scope selector is disabled for JSON files (A19).
 */
public final class SearchViewModel {

    /** Levels offered as checkboxes. */
    public static final List<Level> LEVELS = List.of(Level.TRACE, Level.DEBUG, Level.INFO, Level.WARN, Level.ERROR,
            Level.FATAL);

    /** One field filter row; values are comma-separated and OR'd. */
    public static final class FieldRow {
        private final StringProperty field = new SimpleStringProperty("");
        private final ObjectProperty<MatchMode> mode = new SimpleObjectProperty<>(MatchMode.EXACT);
        private final StringProperty values = new SimpleStringProperty("");

        public StringProperty fieldProperty() {
            return field;
        }

        public ObjectProperty<MatchMode> modeProperty() {
            return mode;
        }

        public StringProperty valuesProperty() {
            return values;
        }

        boolean isBlank() {
            return field.get().isBlank() && values.get().isBlank();
        }

        List<String> valueList() {
            return Arrays.stream(values.get().split(",")).map(String::trim).filter(v -> !v.isEmpty()).toList();
        }
    }

    /** Outcome of {@link #compile()}: exactly one of {@code query} and {@code error} is set. */
    public record Result(CompiledQuery query, String error) {
        public boolean ok() {
            return query != null;
        }
    }

    private final ObservableList<FieldRow> fieldRows = FXCollections.observableArrayList(
            row -> new Observable[]{row.field, row.mode, row.values});
    private final StringProperty text = new SimpleStringProperty("");
    private final BooleanProperty regex = new SimpleBooleanProperty(false);
    private final BooleanProperty caseSensitive = new SimpleBooleanProperty(false);
    private final BooleanProperty dotAll = new SimpleBooleanProperty(false);
    private final ObjectProperty<Scope> scope = new SimpleObjectProperty<>(Scope.ALL);
    private final Map<Level, BooleanProperty> levels = new EnumMap<>(Level.class);
    private final StringProperty from = new SimpleStringProperty("");
    private final StringProperty to = new SimpleStringProperty("");
    private final ObjectProperty<ZoneId> zone = new SimpleObjectProperty<>(ParseContext.DEFAULT_ZONE);
    private final ObjectProperty<LogFormat> format = new SimpleObjectProperty<>(LogFormat.PLAIN);
    private final ReadOnlyStringWrapper validationMessage = new ReadOnlyStringWrapper("");
    private final BooleanBinding scopeDisabled = Bindings.createBooleanBinding(
            () -> format.get() == LogFormat.JSON, format);

    public SearchViewModel() {
        for (Level level : LEVELS) {
            levels.put(level, new SimpleBooleanProperty(false));
        }
        fieldRows.add(new FieldRow());
        List<Observable> deps = new ArrayList<>(List.of(fieldRows, text, regex, caseSensitive, dotAll, scope, from, to,
                zone, format));
        deps.addAll(levels.values());
        deps.forEach(o -> o.addListener(x -> validate()));
    }

    public ObservableList<FieldRow> fieldRows() {
        return fieldRows;
    }

    public FieldRow addFieldRow() {
        FieldRow row = new FieldRow();
        fieldRows.add(row);
        return row;
    }

    public StringProperty textProperty() {
        return text;
    }

    public BooleanProperty regexProperty() {
        return regex;
    }

    public BooleanProperty caseSensitiveProperty() {
        return caseSensitive;
    }

    public BooleanProperty dotAllProperty() {
        return dotAll;
    }

    public ObjectProperty<Scope> scopeProperty() {
        return scope;
    }

    public BooleanProperty levelProperty(Level level) {
        return levels.get(level);
    }

    public StringProperty fromProperty() {
        return from;
    }

    public StringProperty toProperty() {
        return to;
    }

    public ObjectProperty<ZoneId> zoneProperty() {
        return zone;
    }

    public ObjectProperty<LogFormat> formatProperty() {
        return format;
    }

    public BooleanBinding scopeDisabledProperty() {
        return scopeDisabled;
    }

    public ReadOnlyStringProperty validationMessageProperty() {
        return validationMessage.getReadOnlyProperty();
    }

    /** Re-validates the inputs and updates {@link #validationMessageProperty()}. */
    public void validate() {
        Result r = compile();
        validationMessage.set(r.ok() ? "" : r.error());
    }

    /** Builds the {@link Query} from the inputs. Throws {@link IllegalArgumentException} for invalid input. */
    public Query toQuery() {
        List<FieldFilter> filters = new ArrayList<>();
        for (FieldRow row : fieldRows) {
            if (row.isBlank()) {
                continue;
            }
            String field = row.field.get().trim();
            List<String> values = row.valueList();
            if (field.isEmpty()) {
                throw new IllegalArgumentException("Field name is missing for value '" + row.values.get().trim() + "'");
            }
            if (values.isEmpty()) {
                throw new IllegalArgumentException("Enter at least one value for field '" + field + "'");
            }
            filters.add(new FieldFilter(field, row.mode.get(), values));
        }
        Query q = Query.matchAll().withFields(filters).withLevels(selectedLevels());
        if (!text.get().isEmpty()) {
            boolean isRegex = regex.get();
            q = q.withText(new TextQuery(text.get(), isRegex, caseSensitive.get(), isRegex && dotAll.get(),
                    effectiveScope()));
        }
        Instant f = from.get().isBlank() ? null : TimeParser.parse(from.get(), zone.get());
        Instant t = to.get().isBlank() ? null : TimeParser.parse(to.get(), zone.get());
        if (f != null && t != null && !f.isBefore(t)) {
            throw new IllegalArgumentException("'From' must be before 'To'");
        }
        return q.withRange(f, t);
    }

    /** Validates and compiles the query for this tab's format. */
    public Result compile() {
        try {
            return new Result(QueryCompiler.compile(toQuery()).forFormat(format.get()), null);
        } catch (QuerySyntaxException e) {
            String where = e.index() >= 0 ? " at position " + e.index() : "";
            String what = e.pattern() == null ? e.getMessage() : "Invalid regex: " + e.description() + where;
            return new Result(null, what);
        } catch (IllegalArgumentException e) {
            return new Result(null, e.getMessage());
        }
    }

    private Scope effectiveScope() {
        return format.get() == LogFormat.JSON ? Scope.ALL : scope.get();
    }

    private Set<Level> selectedLevels() {
        Set<Level> out = EnumSet.noneOf(Level.class);
        levels.forEach((level, selected) -> {
            if (selected.get()) {
                out.add(level);
            }
        });
        return out;
    }
}
