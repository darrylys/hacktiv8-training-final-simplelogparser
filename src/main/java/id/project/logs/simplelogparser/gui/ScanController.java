package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanException;
import id.project.logs.simplelogparser.scan.ScanHandle;
import id.project.logs.simplelogparser.scan.ScanListener;
import id.project.logs.simplelogparser.scan.ScanRequest;
import id.project.logs.simplelogparser.scan.ScanSummary;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * Runs the scans of one tab. Starting a search cancels the running scan first, and every scan is a separate
 * session, so hits from an older search never reach the list (edge #19). Hits are handed to the UI thread
 * at most every {@link #FLUSH_MILLIS} ms, so the FX thread never blocks (PRD 6). All properties are changed
 * on the {@link UiThread} only.
 */
public final class ScanController {

    static final long FLUSH_MILLIS = 100;

    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "slp-gui-flush");
        t.setDaemon(true);
        return t;
    });

    private final ScanEngine engine;
    private final UiThread ui;
    private final Path file;
    private final LogFormat format;
    private final ParseContext ctx;
    private final IntSupplier resultCap;

    private final ObservableList<Hit> hits = FXCollections.observableArrayList();
    private final DoubleProperty progress = new SimpleDoubleProperty(0);
    private final BooleanProperty running = new SimpleBooleanProperty(false);
    private final ObjectProperty<ScanSummary> summary = new SimpleObjectProperty<>();
    private final StringProperty error = new SimpleStringProperty("");
    private final ObjectProperty<CompiledQuery> query = new SimpleObjectProperty<>();

    private volatile Session current;
    private long generation;

    public ScanController(ScanEngine engine, UiThread ui, Path file, LogFormat format, ParseContext ctx,
                          IntSupplier resultCap) {
        this.engine = engine;
        this.ui = ui;
        this.file = file;
        this.format = format;
        this.ctx = ctx;
        this.resultCap = resultCap;
    }

    public ObservableList<Hit> hits() {
        return hits;
    }

    public DoubleProperty progressProperty() {
        return progress;
    }

    public BooleanProperty runningProperty() {
        return running;
    }

    public ObjectProperty<ScanSummary> summaryProperty() {
        return summary;
    }

    public StringProperty errorProperty() {
        return error;
    }

    /** Query of the most recent search, used for highlighting. */
    public ObjectProperty<CompiledQuery> queryProperty() {
        return query;
    }

    public int resultCap() {
        return resultCap.getAsInt();
    }

    /** Number of the most recent search; increases with every {@link #start}. */
    public long generation() {
        return generation;
    }

    /** Cancels any running scan and starts a new one. Call on the UI thread. */
    public void start(CompiledQuery compiled) {
        cancel();
        Session session = new Session(++generation);
        current = session;
        hits.clear();
        summary.set(null);
        error.set("");
        progress.set(0);
        running.set(true);
        query.set(compiled);
        ScanRequest request = ScanRequest.of(file, format, compiled).withContext(ctx).withResultCap(resultCap.getAsInt());
        try {
            session.handle = engine.start(request, session);
        } catch (IOException e) {
            running.set(false);
            current = null;
            error.set("Could not read " + file + ": " + e.getMessage());
            return;
        }
        session.handle.done().whenComplete((s, t) -> {
            if (t != null) {
                Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
                ui.runLater(() -> session.fail(cause));
            }
        });
    }

    /** Cancels the running scan, if any; its remaining hits are dropped. */
    public void cancel() {
        Session s = current;
        if (s != null && s.handle != null) {
            s.handle.cancel();
        }
    }

    /** Cancels and forgets the running scan (tab closed). */
    public void dispose() {
        cancel();
        current = null;
    }

    /** One scan. Buffers hits from the scan thread and drains them on the UI thread. */
    private final class Session implements ScanListener {
        final long gen;
        volatile ScanHandle handle;
        private final List<Hit> pending = new ArrayList<>();
        private boolean flushScheduled;
        private long lastFlush;
        private volatile double lastProgress;

        Session(long gen) {
            this.gen = gen;
        }

        private boolean isCurrent() {
            return current == this;
        }

        @Override
        public void onHits(List<Hit> batch) {
            synchronized (this) {
                pending.addAll(batch);
                if (flushScheduled) {
                    return;
                }
                flushScheduled = true;
            }
            long wait = Math.max(0, FLUSH_MILLIS - (System.currentTimeMillis() - lastFlush));
            TIMER.schedule(() -> ui.runLater(this::drain), wait, TimeUnit.MILLISECONDS);
        }

        @Override
        public void onProgress(double fraction) {
            lastProgress = fraction;
        }

        @Override
        public void onFinished(ScanSummary s) {
            ui.runLater(() -> {
                drain();
                if (isCurrent()) {
                    summary.set(s);
                    running.set(false);
                }
            });
        }

        /** Moves pending hits into the list; runs on the UI thread. */
        private void drain() {
            List<Hit> batch;
            synchronized (this) {
                batch = new ArrayList<>(pending);
                pending.clear();
                flushScheduled = false;
                lastFlush = System.currentTimeMillis();
            }
            if (!isCurrent()) {
                return;
            }
            if (!batch.isEmpty()) {
                hits.addAll(batch);
            }
            progress.set(lastProgress);
        }

        private void fail(Throwable t) {
            if (!isCurrent()) {
                return;
            }
            drain();
            running.set(false);
            error.set(t instanceof ScanException ? t.getMessage() : String.valueOf(t.getMessage()));
        }
    }
}
