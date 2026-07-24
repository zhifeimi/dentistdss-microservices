package press.mizhifei.dentist.genai.repository;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import press.mizhifei.dentist.genai.model.Conversation;
import reactor.core.publisher.Flux;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
public interface ConversationRepository extends ReactiveMongoRepository<Conversation, String> {
    Flux<Conversation> findBySessionIdAndUserIdAndClinicIdAndAgent(
            String sessionId,
            String userId,
            String clinicId,
            String agent);

    Flux<Conversation> findBySessionIdAndUserIdIsNullAndClinicIdIsNullAndAgent(
            String sessionId,
            String agent);
} 