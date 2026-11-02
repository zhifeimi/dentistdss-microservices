package press.mizhifei.dentist.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SignUpClinicAdminRequest {


    @NotBlank(message = "Clinic name is required")
    private String clinicName;

    @NotBlank(message = "Address is required")
    private String address;

    @NotBlank(message = "City is required")
    private String city;

    @NotBlank(message = "State is required")
    private String state;

    @NotBlank(message = "Zip code is required")
    private String zipCode;

    @NotBlank(message = "Country is required")
    private String country;

    @NotBlank(message = "Phone number is required")
    private String phoneNumber;

    @NotBlank(message = "Business email is required")
    @Email(message = "Business email should be valid")
    private String businessEmail;

    private String website;

    /**
     * Agreement to "Show my clinic in public search" in the registration
     * terms. Omitted or false keeps the clinic out of the public directory.
     */
    private Boolean listedPublicly;
    
    

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Email should be valid")
    private String email;

    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;
    
    
    

}