package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.query.CancelToken;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.Highlighter;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.MatchTimeoutException;
import id.project.logs.simplelogparser.scan.EntryReader;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.StyleClassedTextArea;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Read-only, virtualized view of one entry with highlighted matches. The entry is re-read and highlighted in
 * the background; only setting text and styles runs on the FX thread. Shows the {@link DisplayText} (LF
 * line endings; JSON escapes for line breaks and tabs rendered), with a {@code [truncated]} marker for
 * entries over the entry limit. A "Word wrap" checkbox wraps long lines; its state is shared by all tabs.
 */
public final class DetailPane extends BorderPane {

    static final String TRUNCATED_MARKER = "\n[truncated]";
    private static final Collection<String> MATCH = List.of("match");

    private final StyleClassedTextArea area = new StyleClassedTextArea();
    private final CheckBox wordWrap = new CheckBox("Word wrap");
    private final EntryReader reader;
    private final Executor background;
    private final UiThread ui;
    private final Duration regexTimeout;
    private final AtomicLong generation = new AtomicLong();

    public DetailPane(EntryReader reader, Executor background, UiThread ui, Duration regexTimeout,
                      BooleanProperty wordWrapSetting) {
        this.reader = reader;
        this.background = background;
        this.ui = ui;
        this.regexTimeout = regexTimeout;
        area.setId("detail");
        area.setEditable(false);
        area.getStyleClass().add("detail-area");
        wordWrap.setId("word-wrap");
        wordWrap.setTooltip(new Tooltip("Wrap long lines in the entry view"));
        wordWrap.selectedProperty().bindBidirectional(wordWrapSetting);
        area.wrapTextProperty().bind(wordWrap.selectedProperty());
        HBox toolbar = new HBox(8, wordWrap);
        toolbar.setAlignment(Pos.CENTER_RIGHT);
        toolbar.setPadding(new Insets(2, 8, 2, 8));
        toolbar.getStyleClass().add("detail-toolbar");
        setTop(toolbar);
        setCenter(new VirtualizedScrollPane<>(area));
    }

    public StyleClassedTextArea area() {
        return area;
    }

    public CheckBox wordWrap() {
        return wordWrap;
    }

    public void clear() {
        generation.incrementAndGet();
        area.clear();
    }

    /** Shows {@code hit}, highlighting {@code query}'s matches. A later call supersedes a pending one. */
    public void show(Hit hit, CompiledQuery query) {
        long gen = generation.incrementAndGet();
        background.execute(() -> {
            try {
                LogEntry entry = reader.read(hit);
                MatchBudget budget = new MatchBudget(regexTimeout, new CancelToken());
                DisplayText display;
                try {
                    List<Highlighter.Range> ranges = query == null ? List.of() : Highlighter.ranges(entry, query, budget);
                    budget.startEntry();
                    display = DisplayText.of(entry, ranges, query, budget);
                } catch (MatchTimeoutException e) {
                    display = DisplayText.of(entry, List.of(), null, budget);
                }
                String text = display.text() + (hit.truncated() ? TRUNCATED_MARKER : "");
                List<Highlighter.Range> found = display.ranges();
                ui.runLater(() -> render(gen, text, found));
            } catch (Exception e) {
                ui.runLater(() -> render(gen, "Could not read the entry: " + e.getMessage(), List.of()));
            }
        });
    }

    private void render(long gen, String text, List<Highlighter.Range> ranges) {
        if (gen != generation.get()) {
            return;
        }
        area.replaceText(text);
        for (Highlighter.Range r : ranges) {
            if (r.end() <= text.length()) {
                area.setStyle(r.start(), r.end(), MATCH);
            }
        }
        int caret = ranges.isEmpty() ? 0 : ranges.get(0).start();
        area.moveTo(caret);
        area.requestFollowCaret();
    }
}
