package id.project.logs.simplelogparser.query;

/**
 * Wraps the text a regex runs against so that the match stops itself on the per-entry deadline or on
 * cancel (PRD 5.4, edge #14). {@code Matcher} ignores interrupts, so the check has to come from inside.
 */
public final class GuardedCharSequence implements CharSequence {

    private final CharSequence base;
    private final int start;
    private final int end;
    private final MatchBudget budget;

    public GuardedCharSequence(CharSequence base, MatchBudget budget) {
        this(base, 0, base.length(), budget);
    }

    private GuardedCharSequence(CharSequence base, int start, int end, MatchBudget budget) {
        this.base = base;
        this.start = start;
        this.end = end;
        this.budget = budget;
    }

    @Override
    public int length() {
        return end - start;
    }

    @Override
    public char charAt(int index) {
        budget.tick();
        return base.charAt(start + index);
    }

    @Override
    public CharSequence subSequence(int from, int to) {
        if (from < 0 || to > length() || from > to) {
            throw new IndexOutOfBoundsException("[" + from + ", " + to + ") of " + length());
        }
        return new GuardedCharSequence(base, start + from, start + to, budget);
    }

    @Override
    public String toString() {
        return base.subSequence(start, end).toString();
    }
}
