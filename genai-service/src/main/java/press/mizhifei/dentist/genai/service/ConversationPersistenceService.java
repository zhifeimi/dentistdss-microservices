package press.mizhifei.dentist.genai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.repository.ConversationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class ConversationPersistenceService {

    private final ConversationRepository conversationRepository;
    private final ReactiveMongoTemplate mongoTemplate;
    private final int maxHistoryMessages;
    private final int maxStoredMessages;

    public ConversationPersistenceService(
            ConversationRepository conversationRepository,
            ReactiveMongoTemplate mongoTemplate,
            @Value("${genai.conversation.max-history-messages:10}")
            int maxHistoryMessages,
            @Value("${genai.conversation.max-stored-messages:50}")
            int maxStoredMessages) {
        if (maxHistoryMessages < 0 || maxStoredMessages < 2) {
            throw new IllegalArgumentException("Invalid GenAI conversation limits");
        }
        this.conversationRepository = conversationRepository;
        this.mongoTemplate = mongoTemplate;
        this.maxHistoryMessages = Math.min(maxHistoryMessages, maxStoredMessages);
        this.maxStoredMessages = maxStoredMessages;
    }

    public Mono<OpenConversation> openConversation(
            UserContextService.UserContext userContext,
            String agent,
            String prompt,
            String turnId) {
        return findOwnedConversations(userContext, agent)
                .filter(candidate -> isOwnedConversation(candidate, agent, userContext))
                .collectList()
                .map(conversations -> selectOrCreateConversation(
                        conversations, agent, userContext))
                .flatMap(conversation -> {
                    List<Conversation.Message> history = history(conversation);
                    Conversation.Message userMessage = message(
                            "user", prompt, turnId, null);
                    return appendMessage(conversation, userContext, agent, userMessage)
                            .map(saved -> new OpenConversation(
                                    saved,
                                    history,
                                    turnId));
                });
    }

    public Mono<Conversation> appendAssistantMessage(
            OpenConversation openConversation,
            UserContextService.UserContext userContext,
            String agent,
            String response,
            InteractionStatus status) {
        Conversation.Message assistantMessage = message(
                "assistant", response, openConversation.turnId(), status);
        return appendMessage(
                openConversation.conversation(),
                userContext,
                agent,
                assistantMessage);
    }

    private Flux<Conversation> findOwnedConversations(
            UserContextService.UserContext userContext,
            String agent) {
        if (userContext.isAuthenticated()) {
            return conversationRepository.findBySessionIdAndUserIdAndClinicIdAndAgent(
                    userContext.getSessionId(),
                    userContext.getUserId(),
                    userContext.getClinicId(),
                    agent);
        }
        return conversationRepository
                .findBySessionIdAndUserIdIsNullAndClinicIdIsNullAndAgent(
                        userContext.getSessionId(),
                        agent);
    }

    private Conversation selectOrCreateConversation(
            List<Conversation> conversations,
            String agent,
            UserContextService.UserContext userContext) {
        return conversations.stream()
                .max(Comparator.comparing(
                        this::lastUpdatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())))
                .orElseGet(() -> newConversation(agent, userContext));
    }

    private Mono<Conversation> appendMessage(
            Conversation conversation,
            UserContextService.UserContext userContext,
            String agent,
            Conversation.Message message) {
        String conversationId = Objects.requireNonNullElseGet(
                conversation.getId(),
                () -> conversationId(agent, userContext));
        Instant now = Instant.now();
        Query query = ownedConversationQuery(
                conversationId, userContext, agent);
        Update update = new Update()
                .setOnInsert("createdAt", now)
                .set("updatedAt", now);
        update.push("messages")
                .slice(-maxStoredMessages)
                .each(message);

        return mongoTemplate.findAndModify(
                        query,
                        update,
                        FindAndModifyOptions.options()
                                .upsert(true)
                                .returnNew(true),
                        Conversation.class)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Conversation append returned no result")));
    }

    private Query ownedConversationQuery(
            String conversationId,
            UserContextService.UserContext userContext,
            String agent) {
        Criteria criteria = Criteria.where("_id").is(conversationId)
                .and("sessionId").is(userContext.getSessionId())
                .and("userId").is(userContext.getUserId())
                .and("clinicId").is(userContext.getClinicId())
                .and("agent").is(agent);
        return Query.query(criteria);
    }

    private Conversation newConversation(
            String agent,
            UserContextService.UserContext userContext) {
        Instant now = Instant.now();
        Conversation conversation = new Conversation();
        conversation.setId(conversationId(agent, userContext));
        conversation.setSessionId(userContext.getSessionId());
        conversation.setUserId(userContext.getUserId());
        conversation.setClinicId(userContext.getClinicId());
        conversation.setAgent(agent);
        conversation.setMessages(new ArrayList<>());
        conversation.setCreatedAt(now);
        conversation.setUpdatedAt(now);
        return conversation;
    }

    private boolean isOwnedConversation(
            Conversation conversation,
            String agent,
            UserContextService.UserContext userContext) {
        if (!Objects.equals(conversation.getSessionId(), userContext.getSessionId())
                || !Objects.equals(conversation.getAgent(), agent)) {
            return false;
        }
        if (!userContext.isAuthenticated()) {
            return conversation.getUserId() == null
                    && conversation.getClinicId() == null;
        }
        return Objects.equals(conversation.getUserId(), userContext.getUserId())
                && Objects.equals(conversation.getClinicId(), userContext.getClinicId());
    }

    private List<Conversation.Message> history(Conversation conversation) {
        List<Conversation.Message> messages = conversation.getMessages();
        if (messages == null || messages.isEmpty() || maxHistoryMessages == 0) {
            return List.of();
        }
        int fromIndex = Math.max(0, messages.size() - maxHistoryMessages);
        return List.copyOf(messages.subList(fromIndex, messages.size()));
    }

    private Instant lastUpdatedAt(Conversation conversation) {
        return conversation.getUpdatedAt() != null
                ? conversation.getUpdatedAt()
                : conversation.getCreatedAt();
    }

    private Conversation.Message message(
            String role,
            String content,
            String turnId,
            InteractionStatus status) {
        Conversation.Message message = new Conversation.Message();
        message.setRole(role);
        message.setContent(content);
        message.setTurnId(turnId);
        message.setStatus(status);
        message.setTimestamp(Instant.now());
        return message;
    }

    private String conversationId(
            String agent,
            UserContextService.UserContext userContext) {
        String ownershipKey = String.join("",
                userContext.getSessionId(),
                Objects.toString(userContext.getUserId(), "anonymous"),
                Objects.toString(userContext.getClinicId(), "none"),
                agent);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("dentistdss-genai-conversation:" + ownershipKey)
                            .getBytes(StandardCharsets.UTF_8));
            return "conv_" + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public record OpenConversation(
            Conversation conversation,
            List<Conversation.Message> history,
            String turnId) {
    }
}
