package press.mizhifei.dentist.clinic.model;

/** Kinds of clinic closure, matching {@code HolidayType} in the frontend. */
public enum HolidayType {
    NATIONAL_HOLIDAY("National holiday"),
    CLINIC_CLOSURE("Clinic closure"),
    STAFF_TRAINING("Staff training"),
    MAINTENANCE("Maintenance"),
    EMERGENCY_CLOSURE("Emergency closure"),
    VACATION("Vacation"),
    OTHER("Other");

    private final String displayName;

    HolidayType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
