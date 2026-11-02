package press.mizhifei.dentist.clinic.directory;

/** Asks the frontend host to rebuild the static (prerendered) site. */
public interface FrontendRebuildTrigger {

    boolean isConfigured();

    /**
     * @throws RebuildTriggerException when the rebuild could not be requested;
     *                                 its message never contains the secret URL
     */
    void trigger();

    class RebuildTriggerException extends RuntimeException {

        public RebuildTriggerException(String message) {
            super(message);
        }
    }
}
