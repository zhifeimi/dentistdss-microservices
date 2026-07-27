package press.mizhifei.dentist.notification.exception;

/**
 * Unchecked failure raised when an email cannot be composed or handed to the
 * mail transport. Deliberately specific rather than a bare
 * {@code RuntimeException} (FindSecBugs THROWS_METHOD_THROWS_RUNTIMEEXCEPTION):
 * callers and Spring's default error handling still observe an unchecked,
 * 500-mapped failure, but the type carries the "email transmission failed"
 * meaning and preserves the originating cause.
 */
public class EmailSendException extends RuntimeException {

    public EmailSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
