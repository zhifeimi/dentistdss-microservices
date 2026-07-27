package press.mizhifei.dentist.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IdTokenRequest {
    @NotBlank
    private String idToken;

    @NotBlank
    private String nonce;
}
