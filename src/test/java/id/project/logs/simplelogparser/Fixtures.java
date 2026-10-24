package id.project.logs.simplelogparser;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.QueryCompiler;
import id.project.logs.simplelogparser.query.QuerySyntaxException;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanListener;
import id.project.logs.simplelogparser.scan.ScanRequest;
import id.project.logs.simplelogparser.scan.ScanSummary;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test helpers: fixture paths and a blocking scan. */
public final class Fixtures {

    private Fixtures() {
    }

    public static Path path(String name) {
        URL url = Fixtures.class.getResource("/fixtures/" + name);
        if (url == null) {
            throw new IllegalArgumentException("missing fixture " + name);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    public static CompiledQuery compile(Query q) {
        try {
            return QueryCompiler.compile(q);
        } catch (QuerySyntaxException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /** Result of a blocking scan. */
    public record Result(List<Hit> hits, ScanSummary summary) {
        public List<Long> lines() {
            return hits.stream().map(Hit::lineNumber).toList();
        }
    }

    public static Result scan(ScanRequest request) throws Exception {
        List<Hit> hits = Collections.synchronizedList(new ArrayList<>());
        ScanSummary summary = new ScanEngine().run(request, new ScanListener() {
            @Override
            public void onHits(List<Hit> batch) {
                hits.addAll(batch);
            }
        });
        return new Result(List.copyOf(hits), summary);
    }

    public static Result scan(Path file, LogFormat format, Query query) throws Exception {
        CompiledQuery q = compile(query).forFormat(format);
        return scan(ScanRequest.of(file, format, q));
    }

    public static Result scan(Path file, LogFormat format, Query query, long chunkSize, int threads)
            throws Exception {
        CompiledQuery q = compile(query).forFormat(format);
        return scan(ScanRequest.of(file, format, q).withChunkSize(chunkSize).withThreads(threads));
    }
}
