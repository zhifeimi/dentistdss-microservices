package press.mizhifei.dentist.genai.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Data
@NoArgsConstructor
@Document(collection = "conversations")
public class Conversation {
    @Id
    private String id;
    private String sessionId;
    private String userId;
    private String clinicId;
    private String agent; // help, receptionist, aidentist, triage, documentation
    private List<Message> messages;
    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @Data
    @NoArgsConstructor
    public static class Message {
        private String role; // user | assistant
        private String content;
        private String turnId;
        private InteractionStatus status;
        private Instant timestamp = Instant.now();
    }
} 