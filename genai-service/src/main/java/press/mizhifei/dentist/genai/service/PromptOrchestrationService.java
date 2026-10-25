package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import press.mizhifei.dentist.genai.model.PromptTemplate;
import press.mizhifei.dentist.genai.repository.PromptTemplateRepository;
import press.mizhifei.dentist.genai.service.UserContextService.UserContext;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for intelligent prompt orchestration and management
 * Handles dynamic prompt selection, customization, and template processing
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptOrchestrationService {

    private final PromptTemplateRepository promptTemplateRepository;
    private final AIPromptService aiPromptService; // Fallback to existing service
    private final UserContextService userContextService;

    private static final Pattern TEMPLATE_VARIABLE_PATTERN = Pattern.compile("\\{\\{([^}]+)\\}\\}");

    /**
     * Orchestrates prompt selection and customization based on user context
     * @param agentType the AI agent type
     * @param userContext user context information
     * @param userPrompt the user's input prompt
     * @param apiProvidedContext additional context from API
     * @return orchestrated system prompt
     */
    public Mono<String> orchestratePrompt(String agentType, UserContext userContext, 
                                        String userPrompt, String apiProvidedContext) {
        
        String primaryRole = userContextService.getPrimaryRole(userContext);
        String clinicId = userContext.getClinicId();

        log.debug("Orchestrating prompt for agent: {}, role: {}, clinic: {}", 
                agentType, primaryRole, clinicId);

        return findBestTemplate(agentType, primaryRole, clinicId)
                .map(template -> buildCustomizedPrompt(template, userContext, userPrompt, apiProvidedContext))
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    // Fallback to existing static prompts
                    log.debug("No template found, falling back to static prompt for agent: {}", agentType);
                    return aiPromptService.getSystemPrompt(agentType);
                }));
    }

    /**
     * Finds the best matching template for the given criteria
     */
    private Mono<PromptTemplate> findBestTemplate(String agentType, String role, String clinicId) {
        return promptTemplateRepository.findByAgentTypeAndActiveOrderByPriorityDesc(agentType)
                .filter(template -> isTemplateApplicable(template, role, clinicId))
                .next(); // Get the highest priority applicable template
    }

    /**
     * Checks if a template is applicable for the given role and clinic
     */
    private boolean isTemplateApplicable(PromptTemplate template, String role, String clinicId) {
        // Check role compatibility
        boolean roleMatch = template.getSupportedRoles() == null || 
                           template.getSupportedRoles().isEmpty() ||
                           template.getSupportedRoles().contains(role);

        // Check clinic compatibility
        boolean clinicMatch = template.getSupportedClinics() == null ||
                             template.getSupportedClinics().isEmpty() ||
                             (StringUtils.hasText(clinicId) && template.getSupportedClinics().contains(clinicId));

        return roleMatch && clinicMatch;
    }

    /**
     * Builds a customized prompt from template and user context
     */
    private String buildCustomizedPrompt(PromptTemplate template, UserContext userContext, 
                                       String userPrompt, String apiProvidedContext) {
        
        StringBuilder promptBuilder = new StringBuilder();
        
        // Start with base system prompt template
        String basePrompt = template.getSystemPromptTemplate();
        
        // Apply template variable substitution
        Map<String, String> variables = buildTemplateVariables(userContext);
        String processedPrompt = processTemplateVariables(basePrompt, variables);
        promptBuilder.append(processedPrompt);

        // Add role-specific customizations
        String primaryRole = userContextService.getPrimaryRole(userContext);
        if (template.getRoleCustomizations() != null && 
            template.getRoleCustomizations().containsKey(primaryRole)) {
            
            String roleCustomization = template.getRoleCustomizations().get(primaryRole);
            promptBuilder.append("\n\n").append(roleCustomization);
            log.debug("Applied role customization for: {}", primaryRole);
        }

        // Add clinic-specific customizations
        if (StringUtils.hasText(userContext.getClinicId()) && 
            template.getClinicCustomizations() != null &&
            template.getClinicCustomizations().containsKey(userContext.getClinicId())) {
            
            String clinicCustomization = template.getClinicCustomizations().get(userContext.getClinicId());
            promptBuilder.append("\n\n").append(clinicCustomization);
            log.debug("Applied clinic customization for clinic: {}", userContext.getClinicId());
        }

        // Add context template if available
        if (StringUtils.hasText(template.getContextTemplate()) && 
            StringUtils.hasText(apiProvidedContext)) {
            
            String contextSection = processTemplateVariables(template.getContextTemplate(), variables);
            promptBuilder.append("\n\nContext: ").append(contextSection);
        }

        String finalPrompt = promptBuilder.toString();
        log.debug("Built customized prompt for template: {} (length: {})", 
                template.getTemplateName(), finalPrompt.length());
        
        return finalPrompt;
    }

    /**
     * Builds template variables map for substitution
     */
    private Map<String, String> buildTemplateVariables(UserContext userContext) {
        Map<String, String> variables = new HashMap<>();
        
        // User context variables
        variables.put("userDisplayName", userContextService.getDisplayName(userContext));
        variables.put("userRole", userContextService.getPrimaryRole(userContext));
        variables.put("isAuthenticated", String.valueOf(userContext.isAuthenticated()));
        variables.put("clinicId", userContext.getClinicId() != null ? userContext.getClinicId() : "");
        variables.put("sessionId", userContext.getSessionId() != null ? userContext.getSessionId() : "");

        // userPrompt and apiContext are deliberately not substitutable: user
        // text must only reach the model as a user message, never as system.
        
        // System variables
        variables.put("timestamp", java.time.LocalDateTime.now().toString());
        
        return variables;
    }

    /**
     * Processes template variables in the given text
     */
    private String processTemplateVariables(String template, Map<String, String> variables) {
        if (!StringUtils.hasText(template)) {
            return template;
        }

        Matcher matcher = TEMPLATE_VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String variableName = matcher.group(1);
            String replacement = variables.getOrDefault(variableName, "");
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Creates a default template for an agent type if none exists
     */
    public Mono<PromptTemplate> createDefaultTemplate(String agentType) {
        String defaultPrompt = aiPromptService.getSystemPrompt(agentType);
        
        PromptTemplate template = PromptTemplate.builder()
                .agentType(agentType)
                .templateName("default-" + agentType)
                .systemPromptTemplate(defaultPrompt)
                .priority(1)
                .active(true)
                .version("1.0")
                .build();

        return promptTemplateRepository.save(template)
                .doOnSuccess(saved -> log.info("Created default template for agent: {}", agentType));
    }
}
