package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.model.Scope;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Validates and compiles a {@link Query} before any I/O (PRD 5.4, AC7). */
public final class QueryCompiler {

    private QueryCompiler() {
    }

    public static CompiledQuery compile(Query query) throws QuerySyntaxException {
        return compile(query, StandardCharsets.UTF_8);
    }

    /** The byte prefilter is only built for UTF-8 input; other encodings scan without it. */
    public static CompiledQuery compile(Query query, Charset charset) throws QuerySyntaxException {
        boolean prefilterAllowed = charset.equals(StandardCharsets.UTF_8);
        BytePrefilter.Builder prefilter = new BytePrefilter.Builder();

        List<CompiledQuery.CompiledField> fields = new ArrayList<>();
        for (FieldFilter f : query.fields()) {
            if (f.field() == null || f.field().isBlank()) {
                throw new QuerySyntaxException("field name must not be empty");
            }
            if (f.values().isEmpty()) {
                throw new QuerySyntaxException("field filter on '" + f.field() + "' has no values");
            }
            List<String> values = f.values().stream().map(String::trim).toList();
            List<Pattern> patterns = new ArrayList<>();
            if (f.mode() == MatchMode.REGEX) {
                for (String v : f.values()) {
                    patterns.add(compileRegex(v, 0));
                }
            } else if (prefilterAllowed) {
                prefilter.require(values, false, true);
            }
            fields.add(new CompiledQuery.CompiledField(f.field().trim(), f.mode(), values, patterns));
        }

        Pattern textPattern = null;
        Scope scope = Scope.ALL;
        if (query.text().isPresent()) {
            TextQuery t = query.text().get();
            scope = t.scope();
            int flags = t.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
            if (t.dotAll()) {
                flags |= Pattern.DOTALL;
            }
            if (t.regex()) {
                textPattern = compileRegex(t.pattern(), flags);
            } else {
                textPattern = Pattern.compile(Pattern.quote(t.pattern()), flags);
                if (prefilterAllowed) {
                    prefilter.require(List.of(t.pattern()), !t.caseSensitive(), false);
                }
            }
        }
        return new CompiledQuery(query, fields, textPattern, scope, prefilter.build(), null);
    }

    private static Pattern compileRegex(String pattern, int flags) throws QuerySyntaxException {
        try {
            return Pattern.compile(pattern, flags);
        } catch (PatternSyntaxException e) {
            throw new QuerySyntaxException(pattern, e.getDescription(), e.getIndex());
        }
    }
}
