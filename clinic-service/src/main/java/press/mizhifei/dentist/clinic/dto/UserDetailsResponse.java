package press.mizhifei.dentist.clinic.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserDetailsResponse {
    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private String address;
    private Set<String> roles;
    private Long clinicId;
    private String clinicName;
    private Boolean enabled;
}
