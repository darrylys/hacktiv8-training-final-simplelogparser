package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.query.MatchMode;
import javafx.beans.binding.Bindings;
import javafx.beans.property.StringProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.Locale;

/** Query inputs (TRD 7.2), bound to a {@link SearchViewModel}. Enter in any text field runs the search. */
public final class SearchPane extends GridPane {

    private final SearchViewModel vm;
    private final Runnable onSearch;
    private final VBox fieldRowsBox = new VBox(4);
    private final Button searchButton = new Button("Search");

    public SearchPane(SearchViewModel vm, Runnable onSearch) {
        this.vm = vm;
        this.onSearch = onSearch;
        getStyleClass().add("search-pane");
        setHgap(8);
        setVgap(6);
        setPadding(new Insets(8));
        ColumnConstraints labels = new ColumnConstraints();
        ColumnConstraints inputs = new ColumnConstraints();
        inputs.setHgrow(Priority.ALWAYS);
        getColumnConstraints().addAll(labels, inputs);

        // fields
        Button addRow = new Button("+");
        addRow.setTooltip(new Tooltip("Add a field filter (different fields are AND'd)"));
        addRow.setOnAction(e -> vm.addFieldRow());
        vm.fieldRows().addListener((ListChangeListener<SearchViewModel.FieldRow>) c -> {
            while (c.next()) {
                if (c.wasAdded() || c.wasRemoved()) {
                    rebuildFieldRows();
                    break;
                }
            }
        });
        rebuildFieldRows();
        HBox fields = new HBox(6, fieldRowsBox, addRow);
        HBox.setHgrow(fieldRowsBox, Priority.ALWAYS);
        addRow(0, new Label("Fields:"), fields);

        // text
        TextField text = textField("text or pattern", vm.textProperty());
        text.setId("text");
        HBox.setHgrow(text, Priority.ALWAYS);
        ToggleGroup kind = new ToggleGroup();
        RadioButton literal = new RadioButton("Literal");
        RadioButton regex = new RadioButton("Regex");
        regex.setId("regex");
        literal.setToggleGroup(kind);
        regex.setToggleGroup(kind);
        literal.setSelected(!vm.regexProperty().get());
        regex.setSelected(vm.regexProperty().get());
        kind.selectedToggleProperty().addListener((o, a, b) -> vm.regexProperty().set(b == regex));
        CheckBox caseSensitive = new CheckBox("Case-sensitive");
        caseSensitive.selectedProperty().bindBidirectional(vm.caseSensitiveProperty());
        CheckBox dotAll = new CheckBox("DOTALL");
        dotAll.setTooltip(new Tooltip("'.' also matches line breaks"));
        dotAll.selectedProperty().bindBidirectional(vm.dotAllProperty());
        dotAll.disableProperty().bind(vm.regexProperty().not());
        HBox textRow = new HBox(8, text, literal, regex, caseSensitive, dotAll);
        textRow.setAlignment(Pos.CENTER_LEFT);
        addRow(1, new Label("Text:"), textRow);

        // scope and levels
        ToggleGroup scopes = new ToggleGroup();
        HBox scopeRow = new HBox(8);
        scopeRow.setAlignment(Pos.CENTER_LEFT);
        for (Scope s : Scope.values()) {
            RadioButton b = new RadioButton(s.name().charAt(0) + s.name().substring(1).toLowerCase(Locale.ROOT));
            b.setId("scope-" + s.name().toLowerCase(Locale.ROOT));
            b.setUserData(s);
            b.setToggleGroup(scopes);
            b.setSelected(vm.scopeProperty().get() == s);
            b.disableProperty().bind(vm.scopeDisabledProperty());
            scopeRow.getChildren().add(b);
        }
        scopes.selectedToggleProperty().addListener((o, a, b) -> {
            if (b != null) {
                vm.scopeProperty().set((Scope) b.getUserData());
            }
        });
        Label scopeNote = new Label("(JSON logs have no request/response sections)");
        scopeNote.getStyleClass().add("hint");
        scopeNote.visibleProperty().bind(vm.scopeDisabledProperty());
        scopeNote.managedProperty().bind(scopeNote.visibleProperty());
        scopeRow.getChildren().addAll(scopeNote, spacer(), new Label("Level:"));
        for (Level level : SearchViewModel.LEVELS) {
            CheckBox cb = new CheckBox(level.name());
            cb.selectedProperty().bindBidirectional(vm.levelProperty(level));
            scopeRow.getChildren().add(cb);
        }
        addRow(2, new Label("Scope:"), scopeRow);

        // time range and search
        String zone = vm.zoneProperty().get().getId();
        TextField from = textField("e.g. 2026-10-18T00:00", vm.fromProperty());
        TextField to = textField("e.g. 2026-10-18T06:00", vm.toProperty());
        Tooltip timeTip = new Tooltip("ISO-8601. Times without an offset are in " + zone
                + ". From is inclusive, To is exclusive.");
        from.setTooltip(timeTip);
        to.setTooltip(timeTip);
        Label zoneLabel = new Label("(" + zone + ")");
        zoneLabel.getStyleClass().add("hint");
        searchButton.setId("search");
        searchButton.setDefaultButton(false);
        searchButton.disableProperty().bind(Bindings.isNotEmpty(vm.validationMessageProperty()));
        searchButton.setOnAction(e -> onSearch.run());
        Label validation = new Label();
        validation.setId("validation");
        validation.getStyleClass().add("validation");
        validation.textProperty().bind(vm.validationMessageProperty());
        HBox timeRow = new HBox(8, new Label("From:"), from, new Label("To:"), to, zoneLabel, spacer(), validation,
                searchButton);
        timeRow.setAlignment(Pos.CENTER_LEFT);
        addRow(3, new Label(""), timeRow);
        vm.validate();
    }

    private void rebuildFieldRows() {
        fieldRowsBox.getChildren().clear();
        for (SearchViewModel.FieldRow row : vm.fieldRows()) {
            TextField field = textField("field, e.g. merchantId", row.fieldProperty());
            field.setPrefColumnCount(14);
            field.getStyleClass().add("field-name");
            ComboBox<MatchMode> mode = new ComboBox<>();
            mode.getItems().addAll(MatchMode.values());
            mode.valueProperty().bindBidirectional(row.modeProperty());
            TextField values = textField("values, comma-separated (OR)", row.valuesProperty());
            values.getStyleClass().add("field-values");
            HBox.setHgrow(values, Priority.ALWAYS);
            Button remove = new Button("−");
            remove.setTooltip(new Tooltip("Remove this filter"));
            remove.setOnAction(e -> {
                vm.fieldRows().remove(row);
                if (vm.fieldRows().isEmpty()) {
                    vm.addFieldRow();
                }
            });
            HBox line = new HBox(6, field, mode, values, remove);
            line.setAlignment(Pos.CENTER_LEFT);
            fieldRowsBox.getChildren().add(line);
        }
    }

    private TextField textField(String prompt, StringProperty property) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.textProperty().bindBidirectional(property);
        tf.setOnAction(e -> {
            if (!searchButton.isDisabled()) {
                onSearch.run();
            }
        });
        return tf;
    }

    private static Region spacer() {
        Region r = new Region();
        HBox.setHgrow(r, Priority.ALWAYS);
        return r;
    }
}
