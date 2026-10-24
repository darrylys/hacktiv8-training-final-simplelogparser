package id.project.logs.simplelogparser.model;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/** A parsed entry. Implementations compute text and sections lazily, only when a predicate asks. */
public interface LogEntry {

    long offset();

    /** Decoded entry text with original line endings. */
    String rawText();

    /** Text that queries run against: line endings normalized to {@code \n} (edge #10). */
    String matchText();

    Optional<Instant> timestamp();

    Level level();

    Optional<String> field(String name);

    Set<String> fieldNames();

    /** The scope's part of {@link #matchText()}; empty when the entry has no such section. */
    CharSequence section(Scope scope);

    /** Start offset of {@link #section(Scope)} within {@link #matchText()}, or -1 when absent. */
    int sectionStart(Scope scope);

    boolean truncated();
}
