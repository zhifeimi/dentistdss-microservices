package press.mizhifei.dentist.appointment.exception;

public class AppointmentDependencyUnavailableException extends RuntimeException {

    public AppointmentDependencyUnavailableException() {
        super("Appointment validation is temporarily unavailable");
    }

    public AppointmentDependencyUnavailableException(Throwable cause) {
        super("Appointment validation is temporarily unavailable", cause);
    }
}
