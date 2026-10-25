package press.mizhifei.dentist.clinic.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Jackson 3 binds through a visible all-args constructor, which would leave
 * {@code isActive} null when the request omits it and create a service that
 * can never be booked.
 */
class ServiceRequestJsonTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void omittedIsActiveDefaultsToTrue() {
        ServiceRequest request = mapper.readValue(
                "{\"clinicId\":1,\"name\":\"Scale and clean\",\"durationMinutes\":30,\"price\":120}",
                ServiceRequest.class);

        assertThat(request.getIsActive()).isTrue();
        assertThat(request.getName()).isEqualTo("Scale and clean");
    }

    @Test
    void explicitIsActiveIsKept() {
        ServiceRequest request = mapper.readValue("{\"isActive\":false}", ServiceRequest.class);

        assertThat(request.getIsActive()).isFalse();
    }
}
