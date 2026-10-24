package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.model.Hit;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Copies entries to the system clipboard as plain Unicode text (PRD 5.6, edge #22). Whole entries are
 * re-read from the file, so the original line endings and characters are kept (AC20); several entries are
 * joined in file order with one blank line in the first entry's line ending (A17). A partial selection in
 * the detail pane is copied as displayed, with LF line endings (A18). "Copy all results" checks the total
 * size first and asks before copying more than the threshold (AC21). Reads run in the background.
 */
public final class ClipboardService {

    public static final long DEFAULT_THRESHOLD = 50L * 1024 * 1024;

    /** Receives the text; production code puts it on the system clipboard. */
    @FunctionalInterface
    public interface ClipboardSink {
        void putString(String text);
    }

    /** Asked on the UI thread before a large copy; returns true to go ahead. */
    @FunctionalInterface
    public interface Confirmer {
        boolean confirm(long totalBytes);
    }

    /** Original bytes of a hit's entry. */
    @FunctionalInterface
    public interface EntryBytes {
        byte[] read(Hit hit) throws IOException;
    }

    private final EntryBytes source;
    private final Charset charset;
    private final ClipboardSink sink;
    private final Executor background;
    private final UiThread ui;
    private final long threshold;

    public ClipboardService(EntryBytes source, Charset charset, ClipboardSink sink, Executor background, UiThread ui,
                            long threshold) {
        this.source = source;
        this.charset = charset;
        this.sink = sink;
        this.background = background;
        this.ui = ui;
        this.threshold = threshold;
    }

    /** Copies the given entries. Completes with true once the text is on the clipboard. */
    public CompletableFuture<Boolean> copyEntries(List<Hit> hits) {
        if (hits.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }
        List<Hit> ordered = new ArrayList<>(hits);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        background.execute(() -> {
            try {
                String text = buildText(ordered);
                ui.runLater(() -> {
                    sink.putString(text);
                    done.complete(true);
                });
            } catch (IOException | RuntimeException e) {
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Copies all results, asking first when their total size is above the threshold. Call on the UI thread. */
    public CompletableFuture<Boolean> copyAll(List<Hit> hits, Confirmer confirmer) {
        long total = hits.stream().mapToLong(Hit::byteLength).sum();
        if (total > threshold && !confirmer.confirm(total)) {
            return CompletableFuture.completedFuture(false);
        }
        return copyEntries(hits);
    }

    /** Copies a detail-pane selection as displayed. */
    public void copySelection(String selected) {
        if (selected != null && !selected.isEmpty()) {
            sink.putString(selected);
        }
    }

    /** Clipboard text for the entries, in file order. */
    String buildText(List<Hit> hits) throws IOException {
        List<Hit> ordered = new ArrayList<>(hits);
        ordered.sort(Comparator.comparingLong(Hit::offset));
        StringBuilder sb = new StringBuilder();
        String lineEnding = null;
        for (int i = 0; i < ordered.size(); i++) {
            Hit hit = ordered.get(i);
            String entry = decode(hit);
            if (lineEnding == null) {
                lineEnding = entry.contains("\r\n") ? "\r\n" : "\n";
            }
            if (i > 0) {
                if (sb.charAt(sb.length() - 1) != '\n') {
                    sb.append(lineEnding);
                }
                sb.append(lineEnding);
            }
            sb.append(entry);
        }
        return sb.toString();
    }

    private String decode(Hit hit) throws IOException {
        String s = new String(source.read(hit), charset);
        return hit.offset() == 0 && s.startsWith("﻿") ? s.substring(1) : s;
    }
}
