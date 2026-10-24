package id.project.logs.simplelogparser.format;

/** The file is neither plain nor JSON, or is empty (AC1, edge #12). */
public class FormatDetectionException extends Exception {

    public FormatDetectionException(String message) {
        super(message);
    }
}
