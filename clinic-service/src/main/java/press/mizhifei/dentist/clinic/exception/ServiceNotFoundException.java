package press.mizhifei.dentist.clinic.exception;

public class ServiceNotFoundException extends RuntimeException {

    public ServiceNotFoundException() {
        super("Service not found");
    }
}
