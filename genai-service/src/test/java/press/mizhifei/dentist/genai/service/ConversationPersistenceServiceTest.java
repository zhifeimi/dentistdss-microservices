package press.mizhifei.dentist.genai.service;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import press.mizhifei.dentist.genai.model.Conversation;
import press.mizhifei.dentist.genai.model.InteractionStatus;
import press.mizhifei.dentist.genai.repository.ConversationRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationPersistenceServiceTest {

    private static final String SESSION_ID =
            "abcdefghijklmnopqrstuvwxyzABCDEFGH012345678";

    private ConversationRepository conversationRepository;
    private ReactiveMongoTemplate mongoTemplate;
    private ConversationPersistenceService persistenceService;

    @BeforeEach
    void setUp() {
        conversationRepository = mock(ConversationRepository.class);
        mongoTemplate = mock(ReactiveMongoTemplate.class);
        persistenceService = new ConversationPersistenceService(
                conversationRepository,
                mongoTemplate,
                2,
                3);
    }

    @Test
    void opensNewestOwnedAuthenticatedConversationWithBoundedImmutableHistory() {
        UserContextService.UserContext context = authenticatedContext();
        Conversation owned = conversation(
                "owned-conversation",
                SESSION_ID,
                "42",
                "9",
                "triage",
                Instant.parse("2026-07-16T00:00:00Z"),
                List.of(
                        message("user", "oldest", "turn-1", null),
                        message("assistant", "older", "turn-1", InteractionStatus.COMPLETED),
                        message("user", "recent", "turn-2", null)));
        Conversation wrongClinic = conversation(
                "wrong-clinic",
                SESSION_ID,
                "42",
                "999",
                "triage",
                Instant.parse("2026-07-16T01:00:00Z"),
                List.of(message("user", "foreign", "turn-x", null)));
        Conversation saved = conversation(
                owned.getId(),
                SESSION_ID,
                "42",
                "9",
                "triage",
                Instant.parse("2026-07-16T02:00:00Z"),
                List.of());

        when(conversationRepository
                .findBySessionIdAndUserIdAndClinicIdAndAgent(
                        SESSION_ID, "42", "9", "triage"))
                .thenReturn(Flux.just(wrongClinic, owned));
        whenFindAndModifyReturns(saved);

        StepVerifier.create(persistenceService.openConversation(
                        context,
                        "triage",
                        "current prompt",
                        "turn-3"))
                .assertNext(openConversation -> {
                    assertEquals("owned-conversation",
                            openConversation.conversation().getId());
                    assertEquals(List.of("older", "recent"),
                            openConversation.history().stream()
                                    .map(Conversation.Message::getContent)
                                    .toList());
                    assertThrows(UnsupportedOperationException.class,
                            () -> openConversation.history().add(
                                    new Conversation.Message()));
                    assertEquals("turn-3", openConversation.turnId());
                })
                .verifyComplete();

        verify(conversationRepository)
                .findBySessionIdAndUserIdAndClinicIdAndAgent(
                        SESSION_ID, "42", "9", "triage");
        verify(conversationRepository, never())
                .findBySessionIdAndUserIdIsNullAndClinicIdIsNullAndAgent(
                        any(), any());
        assertAtomicAppend(
                "owned-conversation",
                context,
                "triage",
                "user",
                "current prompt",
                "turn-3",
                null);
    }

    @Test
    void anonymousScopeFiltersForeignCandidatesAndUsesDeterministicId() {
        UserContextService.UserContext context = anonymousContext();
        Conversation foreignUser = conversation(
                "foreign-user",
                SESSION_ID,
                "42",
                null,
                "help",
                Instant.now(),
                List.of());
        Conversation foreignClinic = conversation(
                "foreign-clinic",
                SESSION_ID,
                null,
                "9",
                "help",
                Instant.now(),
                List.of());
        Conversation foreignAgent = conversation(
                "foreign-agent",
                SESSION_ID,
                null,
                null,
                "triage",
                Instant.now(),
                List.of());

        when(conversationRepository
                .findBySessionIdAndUserIdIsNullAndClinicIdIsNullAndAgent(
                        SESSION_ID, "help"))
                .thenReturn(Flux.just(
                        foreignUser,
                        foreignClinic,
                        foreignAgent));
        whenFindAndModifyReturnsConversationFromQuery();

        Mono<String> firstId = persistenceService.openConversation(
                        context, "help", "first", "turn-1")
                .map(open -> open.conversation().getId());
        Mono<String> secondId = persistenceService.openConversation(
                        context, "help", "second", "turn-2")
                .map(open -> open.conversation().getId());

        StepVerifier.create(Mono.zip(firstId, secondId))
                .assertNext(ids -> {
                    assertEquals(ids.getT1(), ids.getT2());
                    assertTrue(ids.getT1().startsWith("conv_"));
                    assertEquals(48, ids.getT1().length());
                })
                .verifyComplete();

        verify(conversationRepository, times(2))
                .findBySessionIdAndUserIdIsNullAndClinicIdIsNullAndAgent(
                        SESSION_ID, "help");
        verify(conversationRepository, never())
                .findBySessionIdAndUserIdAndClinicIdAndAgent(
                        any(), any(), any(), any());

        var queries = forClass(Query.class);
        verify(mongoTemplate, times(2)).findAndModify(
                queries.capture(),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Conversation.class));
        for (Query query : queries.getAllValues()) {
            Document criteria = query.getQueryObject();
            assertEquals(SESSION_ID, criteria.getString("sessionId"));
            assertTrue(criteria.containsKey("userId"));
            assertNull(criteria.get("userId"));
            assertTrue(criteria.containsKey("clinicId"));
            assertNull(criteria.get("clinicId"));
            assertEquals("help", criteria.getString("agent"));
        }
    }

    @Test
    void appendsPartialAssistantOutcomeWithTurnStatusAndStorageBound() {
        UserContextService.UserContext context = authenticatedContext();
        Conversation conversation = conversation(
                "conversation-1",
                SESSION_ID,
                "42",
                "9",
                "aidentist",
                Instant.now(),
                List.of());
        ConversationPersistenceService.OpenConversation openConversation =
                new ConversationPersistenceService.OpenConversation(
                        conversation,
                        List.of(),
                        "turn-7");
        whenFindAndModifyReturns(conversation);

        StepVerifier.create(persistenceService.appendAssistantMessage(
                        openConversation,
                        context,
                        "aidentist",
                        "partial response",
                        InteractionStatus.ERROR))
                .expectNext(conversation)
                .verifyComplete();

        assertAtomicAppend(
                "conversation-1",
                context,
                "aidentist",
                "assistant",
                "partial response",
                "turn-7",
                InteractionStatus.ERROR);
    }

    @Test
    void rejectsInvalidConversationLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationPersistenceService(
                        conversationRepository,
                        mongoTemplate,
                        -1,
                        3));
        assertThrows(IllegalArgumentException.class,
                () -> new ConversationPersistenceService(
                        conversationRepository,
                        mongoTemplate,
                        2,
                        1));
    }

    private void assertAtomicAppend(
            String conversationId,
            UserContextService.UserContext context,
            String agent,
            String role,
            String content,
            String turnId,
            InteractionStatus status) {
        var queryCaptor = forClass(Query.class);
        var updateCaptor = forClass(Update.class);
        var optionsCaptor = forClass(FindAndModifyOptions.class);
        verify(mongoTemplate).findAndModify(
                queryCaptor.capture(),
                updateCaptor.capture(),
                optionsCaptor.capture(),
                eq(Conversation.class));

        Document criteria = queryCaptor.getValue().getQueryObject();
        assertEquals(conversationId, criteria.getString("_id"));
        assertEquals(context.getSessionId(), criteria.getString("sessionId"));
        assertEquals(context.getUserId(), criteria.get("userId"));
        assertEquals(context.getClinicId(), criteria.get("clinicId"));
        assertEquals(agent, criteria.getString("agent"));

        Document update = updateCaptor.getValue().getUpdateObject();
        assertTrue(update.containsKey("$setOnInsert"));
        assertTrue(((Document) update.get("$set"))
                .containsKey("updatedAt"));
        Update.Modifiers messageUpdate = assertInstanceOf(
                Update.Modifiers.class,
                ((Document) update.get("$push")).get("messages"));
        Object slice = messageUpdate.getModifiers().stream()
                .filter(modifier -> "$slice".equals(modifier.getKey()))
                .findFirst()
                .orElseThrow()
                .getValue();
        assertEquals(-3, ((Number) slice).intValue());
        Object each = messageUpdate.getModifiers().stream()
                .filter(modifier -> "$each".equals(modifier.getKey()))
                .findFirst()
                .orElseThrow()
                .getValue();
        Object firstMessage = each instanceof Object[] values
                ? values[0]
                : ((List<?>) each).getFirst();
        Conversation.Message message = assertInstanceOf(
                Conversation.Message.class,
                firstMessage);
        assertEquals(role, message.getRole());
        assertEquals(content, message.getContent());
        assertEquals(turnId, message.getTurnId());
        assertEquals(status, message.getStatus());

        assertTrue(optionsCaptor.getValue().isUpsert());
        assertTrue(optionsCaptor.getValue().isReturnNew());
    }

    private void whenFindAndModifyReturns(Conversation conversation) {
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Conversation.class)))
                .thenReturn(Mono.just(conversation));
    }

    private void whenFindAndModifyReturnsConversationFromQuery() {
        when(mongoTemplate.findAndModify(
                any(Query.class),
                any(Update.class),
                any(FindAndModifyOptions.class),
                eq(Conversation.class)))
                .thenAnswer(invocation -> {
                    Query query = invocation.getArgument(0);
                    Conversation conversation = new Conversation();
                    conversation.setId(query.getQueryObject().getString("_id"));
                    conversation.setSessionId(SESSION_ID);
                    conversation.setAgent("help");
                    conversation.setMessages(new ArrayList<>());
                    return Mono.just(conversation);
                });
    }

    private UserContextService.UserContext authenticatedContext() {
        return UserContextService.UserContext.builder()
                .sessionId(SESSION_ID)
                .userId("42")
                .clinicId("9")
                .roles(List.of("PATIENT"))
                .authenticated(true)
                .build();
    }

    private UserContextService.UserContext anonymousContext() {
        return UserContextService.UserContext.builder()
                .sessionId(SESSION_ID)
                .roles(List.of())
                .authenticated(false)
                .build();
    }

    private Conversation conversation(
            String id,
            String sessionId,
            String userId,
            String clinicId,
            String agent,
            Instant updatedAt,
            List<Conversation.Message> messages) {
        Conversation conversation = new Conversation();
        conversation.setId(id);
        conversation.setSessionId(sessionId);
        conversation.setUserId(userId);
        conversation.setClinicId(clinicId);
        conversation.setAgent(agent);
        conversation.setCreatedAt(updatedAt.minusSeconds(60));
        conversation.setUpdatedAt(updatedAt);
        conversation.setMessages(new ArrayList<>(messages));
        return conversation;
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
        return message;
    }
}
