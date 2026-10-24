package id.project.logs.simplelogparser.cli;

import java.io.Console;
import java.lang.reflect.Method;

/**
 * Decides whether stdout is a terminal. {@code Console.isTerminal()} exists from JDK 22 and is called
 * reflectively because the build targets Java 21; on 22+ {@code System.console()} is non-null even when
 * output is redirected.
 */
public final class TerminalDetector {

    private TerminalDetector() {
    }

    public static boolean stdoutIsTerminal() {
        Console console = System.console();
        if (console == null) {
            return false;
        }
        try {
            Method isTerminal = Console.class.getMethod("isTerminal");
            return (Boolean) isTerminal.invoke(console);
        } catch (NoSuchMethodException e) {
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Color only without {@code --no-color}, without the {@code NO_COLOR} variable, and on a terminal. */
    public static boolean colorEnabled(boolean noColorFlag, boolean stdoutIsTerminal) {
        return !noColorFlag && System.getenv("NO_COLOR") == null && stdoutIsTerminal;
    }
}
