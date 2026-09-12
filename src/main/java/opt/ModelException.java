package opt;

/**
 * Thrown when a model is built inconsistently or violates a modeling rule
 * (duplicate ids, missing required fields, conflicting options, etc.).
 */
public class ModelException extends RuntimeException {

    /** Constructs an exception with the given detail message. */
    public ModelException(String message) {
        super(message);
    }

    /** Constructs an exception with the given detail message and underlying cause. */
    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
