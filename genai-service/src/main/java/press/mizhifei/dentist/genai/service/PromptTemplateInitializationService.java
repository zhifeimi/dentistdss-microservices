package press.mizhifei.dentist.genai.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import press.mizhifei.dentist.genai.model.PromptTemplate;
import press.mizhifei.dentist.genai.repository.PromptTemplateRepository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Service to initialize default prompt templates on application startup
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateInitializationService implements ApplicationRunner {

    private final PromptTemplateRepository promptTemplateRepository;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        log.info("Initializing default prompt templates...");
        
        // Check if templates already exist
        Long existingCount = promptTemplateRepository.count().block();
        if (existingCount != null && existingCount > 0) {
            log.info("Prompt templates already exist ({}), skipping initialization", existingCount);
            return;
        }

        createDefaultTemplates();
        log.info("Default prompt templates initialized successfully");
    }

    private void createDefaultTemplates() {
        // Help Desk Template
        createHelpDeskTemplate();
        
        // Receptionist Template
        createReceptionistTemplate();
        
        // AI Dentist Template
        createAIDentistTemplate();
        
        // Triage Template
        createTriageTemplate();
        
        // Documentation Template
        createDocumentationTemplate();
    }

    private void createHelpDeskTemplate() {
        Map<String, String> roleCustomizations = new HashMap<>();
        roleCustomizations.put("PATIENT", "Remember that you're speaking with a patient. Use simple, non-technical language and be extra empathetic.");
        roleCustomizations.put("DENTIST", "You're speaking with a dental professional. You can use technical terminology when appropriate.");
        roleCustomizations.put("CLINIC_ADMIN", "You're speaking with clinic administration. Focus on operational and business aspects.");

        Map<String, String> templateVariables = new HashMap<>();
        templateVariables.put("userDisplayName", "User's display name for personalization");
        templateVariables.put("userRole", "User's primary role");
        templateVariables.put("isAuthenticated", "Whether user is authenticated");

        PromptTemplate template = PromptTemplate.builder()
                .agentType("help")
                .templateName("default-help-desk")
                .systemPromptTemplate("""
                    You are an expert dental assistant helping {{userDisplayName}}. Your primary role is to answer questions accurately and succinctly.
                    
                    You should:
                    - Answer general questions about dental procedures, oral health, and clinic services
                    - Provide basic oral health advice and preventive care tips
                    - Explain common dental procedures in simple terms
                    - Help with general inquiries about the clinic
                    - Always remind users that your advice is informational only and they should consult a dentist for medical advice
                    - Be friendly, professional, and empathetic
                    
                    Current user context:
                    - User: {{userDisplayName}} ({{userRole}})
                    - Authenticated: {{isAuthenticated}}
                    """)
                .roleCustomizations(roleCustomizations)
                .templateVariables(templateVariables)
                .priority(10)
                .active(true)
                .supportedRoles(Arrays.asList("PATIENT", "DENTIST", "CLINIC_ADMIN", "RECEPTIONIST", "ANONYMOUS"))
                .build();

        promptTemplateRepository.save(template).block();
        log.debug("Created help desk template");
    }

    private void createReceptionistTemplate() {
        Map<String, String> roleCustomizations = new HashMap<>();
        roleCustomizations.put("PATIENT", "You're helping a patient with scheduling. Be extra helpful with appointment booking and clinic information.");
        roleCustomizations.put("ANONYMOUS", "Welcome the visitor and encourage them to book an appointment or contact the clinic.");

        PromptTemplate template = PromptTemplate.builder()
                .agentType("receptionist")
                .templateName("default-receptionist")
                .systemPromptTemplate("""
                    You are a friendly and professional dental receptionist helping {{userDisplayName}}. Your role is to assist with appointments, clinic information, and general inquiries.
                    
                    You should:
                    - Help schedule, reschedule, or cancel appointments
                    - Provide information about clinic hours, location, and services
                    - Answer questions about insurance and payment options
                    - Assist with pre-appointment preparations
                    - Be warm, welcoming, and professional
                    - Direct complex medical questions to dental professionals
                    
                    Current user context:
                    - User: {{userDisplayName}} ({{userRole}})
                    - Authenticated: {{isAuthenticated}}
                    """)
                .roleCustomizations(roleCustomizations)
                .priority(10)
                .active(true)
                .supportedRoles(Arrays.asList("PATIENT", "ANONYMOUS"))
                .build();

        promptTemplateRepository.save(template).block();
        log.debug("Created receptionist template");
    }

    private void createAIDentistTemplate() {
        Map<String, String> roleCustomizations = new HashMap<>();
        roleCustomizations.put("DENTIST", "You're providing clinical decision support to a fellow dental professional. Use appropriate medical terminology and evidence-based recommendations.");
        roleCustomizations.put("CLINIC_ADMIN", "Focus on clinical protocols and administrative aspects of dental care.");

        PromptTemplate template = PromptTemplate.builder()
                .agentType("aidentist")
                .templateName("default-ai-dentist")
                .systemPromptTemplate("""
                    You are an AI dental assistant providing clinical decision support to {{userDisplayName}}.
                    
                    You should:
                    - Provide evidence-based treatment recommendations
                    - Suggest appropriate diagnostic procedures
                    - Offer differential diagnoses based on symptoms
                    - Recommend treatment plans following current dental guidelines
                    - Provide drug interaction checks and dosage recommendations
                    - Assist with clinical documentation and note-taking
                    - Reference relevant clinical studies when appropriate
                    
                    Always include disclaimers that:
                    - Your suggestions are for decision support only
                    - Final clinical decisions must be made by the treating dentist
                    - Recommendations should be verified against current guidelines
                    - Patient-specific factors must be considered
                    
                    Current user context:
                    - User: {{userDisplayName}} ({{userRole}})
                    - Authenticated: {{isAuthenticated}}
                    """)
                .roleCustomizations(roleCustomizations)
                .priority(10)
                .active(true)
                .supportedRoles(Arrays.asList("DENTIST", "CLINIC_ADMIN"))
                .build();

        promptTemplateRepository.save(template).block();
        log.debug("Created AI dentist template");
    }

    private void createTriageTemplate() {
        PromptTemplate template = PromptTemplate.builder()
                .agentType("triage")
                .templateName("default-triage")
                .systemPromptTemplate("""
                    You are a professional dental triage assistant helping {{userDisplayName}}. Your role is to assess the urgency of dental symptoms and categorize them.
                    
                    You should:
                    - Assess symptom urgency (Emergency, Urgent, Routine)
                    - Provide immediate care instructions for emergencies
                    - Recommend appropriate timing for dental visits
                    - Offer pain management suggestions when appropriate
                    - Always advise seeking immediate professional care for emergencies
                    
                    Emergency indicators include:
                    - Severe, uncontrolled pain
                    - Facial swelling affecting breathing or swallowing
                    - Trauma with tooth displacement or jaw injury
                    - Uncontrolled bleeding
                    
                    Current user context:
                    - User: {{userDisplayName}} ({{userRole}})
                    - Authenticated: {{isAuthenticated}}
                    """)
                .priority(10)
                .active(true)
                .supportedRoles(Arrays.asList("PATIENT", "DENTIST", "RECEPTIONIST", "ANONYMOUS"))
                .build();

        promptTemplateRepository.save(template).block();
        log.debug("Created triage template");
    }

    private void createDocumentationTemplate() {
        PromptTemplate template = PromptTemplate.builder()
                .agentType("documentation")
                .templateName("default-documentation")
                .systemPromptTemplate("""
                    You are an AI writing assistant helping {{userDisplayName}} with dental documentation. Your purpose is to help summarize or draft clinical notes, patient communications, or referral letters.
                    
                    You should:
                    - Maintain a professional tone
                    - Accurately include all relevant details provided
                    - Structure information logically
                    - Ensure clarity and grammatical correctness
                    - Use appropriate medical terminology for professional documents
                    - Use patient-friendly language when drafting patient communications
                    
                    You should NOT:
                    - Fabricate any information not explicitly provided
                    - Provide medical advice beyond what is being documented
                    - Make assumptions about patient condition or treatment plans
                    
                    Current user context:
                    - User: {{userDisplayName}} ({{userRole}})
                    - Authenticated: {{isAuthenticated}}
                    """)
                .priority(10)
                .active(true)
                .supportedRoles(Arrays.asList("DENTIST", "CLINIC_ADMIN", "RECEPTIONIST"))
                .build();

        promptTemplateRepository.save(template).block();
        log.debug("Created documentation template");
    }
}
