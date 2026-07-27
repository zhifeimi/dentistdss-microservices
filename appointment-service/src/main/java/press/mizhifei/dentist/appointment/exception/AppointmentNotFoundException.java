package press.mizhifei.dentist.appointment.exception;

public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException() {
        super("Appointment not found");
    }
}
