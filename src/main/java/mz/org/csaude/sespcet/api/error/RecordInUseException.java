package mz.org.csaude.sespcet.api.error;

public class RecordInUseException extends RuntimeException {

    public RecordInUseException() {
        super();
    }

    public RecordInUseException(String message) {
        super(message);
    }

    public RecordInUseException(String message, Throwable cause) {
        super(message, cause);
    }
}
