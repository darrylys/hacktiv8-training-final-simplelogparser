package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.HitSummary;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.SelectionMode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Virtualized, multi-select list of hits. Rows show only the lightweight {@link HitSummary}; the entry text
 * is re-read on selection (edge #21). A banner says when the results are capped (AC14).
 */
public final class ResultListView extends VBox {

    private final ListView<Hit> list = new ListView<>();
    private final Label banner = new Label();

    public ResultListView(ObservableList<Hit> hits) {
        banner.getStyleClass().add("capped-banner");
        banner.setMaxWidth(Double.MAX_VALUE);
        banner.setVisible(false);
        banner.managedProperty().bind(banner.visibleProperty());
        list.setId("results");
        list.setItems(hits);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setPlaceholder(new Label("No results"));
        list.setCellFactory(v -> new HitCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        getChildren().addAll(banner, list);
    }

    public ListView<Hit> list() {
        return list;
    }

    public void showCapped(Integer cap) {
        banner.setVisible(cap != null);
        if (cap != null) {
            banner.setText(String.format(Locale.ROOT,
                    "Results capped at %,d entries. Narrow the search to see everything.", cap));
        }
    }

    static String describe(Hit hit) {
        HitSummary s = hit.summary();
        String ids = s.keyIds().entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(" "));
        StringBuilder sb = new StringBuilder();
        if (!s.timestampText().isEmpty()) {
            sb.append(s.timestampText()).append("  ");
        }
        sb.append(String.format(Locale.ROOT, "%-5s", s.level().name())).append("  ");
        if (!s.logger().isEmpty()) {
            sb.append(shortLogger(s.logger())).append("  ");
        }
        if (!ids.isEmpty()) {
            sb.append('[').append(ids).append("]  ");
        }
        sb.append(s.firstLine());
        if (hit.truncated()) {
            sb.append("  [truncated]");
        }
        return sb.toString();
    }

    private static String shortLogger(String logger) {
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }

    private static final class HitCell extends ListCell<Hit> {
        @Override
        protected void updateItem(Hit hit, boolean empty) {
            super.updateItem(hit, empty);
            getStyleClass().removeIf(c -> c.startsWith("level-"));
            if (empty || hit == null) {
                setText(null);
                setTooltip(null);
                return;
            }
            setText(describe(hit));
            getStyleClass().add("level-" + hit.summary().level().name().toLowerCase(Locale.ROOT));
        }
    }
}
