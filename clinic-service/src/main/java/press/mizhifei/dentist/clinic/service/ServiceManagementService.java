package press.mizhifei.dentist.clinic.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import press.mizhifei.dentist.clinic.dto.ServiceRequest;
import press.mizhifei.dentist.clinic.dto.ServiceResponse;
import press.mizhifei.dentist.clinic.exception.ServiceNotFoundException;
import press.mizhifei.dentist.clinic.repository.ClinicRepository;
import press.mizhifei.dentist.clinic.repository.ServiceRepository;

import java.util.List;
import java.util.stream.Collectors;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ServiceManagementService {
    
    private final ServiceRepository serviceRepository;
    private final ClinicRepository clinicRepository;
    
    @Transactional
    public ServiceResponse createService(ServiceRequest request) {
        // Validate clinic exists
        clinicRepository.findById(request.getClinicId())
                .orElseThrow(() -> new IllegalArgumentException("Clinic not found"));
        
        press.mizhifei.dentist.clinic.model.Service service = press.mizhifei.dentist.clinic.model.Service.builder()
                .clinicId(request.getClinicId())
                .name(request.getName())
                .description(request.getDescription())
                .durationMinutes(request.getDurationMinutes())
                .price(request.getPrice())
                .category(request.getCategory())
                .isActive(request.getIsActive())
                .build();
        
        press.mizhifei.dentist.clinic.model.Service saved = serviceRepository.save(service);
        log.info("Created service {} for clinic {}", saved.getId(), saved.getClinicId());
        
        return toResponse(saved);
    }
    
    @Transactional
    public ServiceResponse updateService(Integer id, ServiceRequest request) {
        press.mizhifei.dentist.clinic.model.Service service = serviceRepository.findById(id)
                .orElseThrow(() -> new ServiceNotFoundException());
        
        service.setName(request.getName());
        service.setDescription(request.getDescription());
        service.setDurationMinutes(request.getDurationMinutes());
        service.setPrice(request.getPrice());
        service.setCategory(request.getCategory());
        service.setIsActive(request.getIsActive());
        
        press.mizhifei.dentist.clinic.model.Service saved = serviceRepository.save(service);
        log.info("Updated service {}", id);
        
        return toResponse(saved);
    }
    
    @Transactional
    public void deleteService(Integer id) {
        press.mizhifei.dentist.clinic.model.Service service = serviceRepository.findById(id)
                .orElseThrow(() -> new ServiceNotFoundException());
        
        // Soft delete by deactivating
        service.setIsActive(false);
        serviceRepository.save(service);
        log.info("Deactivated service {}", id);
    }
    
    @Transactional(readOnly = true)
    public ServiceResponse getService(Integer id) {
        press.mizhifei.dentist.clinic.model.Service service = serviceRepository.findById(id)
                .orElseThrow(() -> new ServiceNotFoundException());
        
        return toResponse(service);
    }
    
    @Transactional(readOnly = true)
    public List<ServiceResponse> getClinicServices(Long clinicId, boolean activeOnly) {
        List<press.mizhifei.dentist.clinic.model.Service> services;
        
        if (activeOnly) {
            services = serviceRepository.findByClinicIdAndIsActiveTrue(clinicId);
        } else {
            services = serviceRepository.findByClinicId(clinicId);
        }
        
        return services.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<ServiceResponse> getServicesByCategory(Long clinicId, String category) {
        List<press.mizhifei.dentist.clinic.model.Service> services = 
                serviceRepository.findByClinicIdAndCategoryAndIsActiveTrue(clinicId, category);
        
        return services.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }
    
    @Transactional(readOnly = true)
    public List<String> getServiceCategories(Long clinicId) {
        return serviceRepository.findDistinctCategoriesByClinicId(clinicId);
    }
    
    private ServiceResponse toResponse(press.mizhifei.dentist.clinic.model.Service service) {
        ServiceResponse response = ServiceResponse.builder()
                .id(service.getId())
                .clinicId(service.getClinicId())
                .name(service.getName())
                .description(service.getDescription())
                .durationMinutes(service.getDurationMinutes())
                .price(service.getPrice())
                .category(service.getCategory())
                .isActive(service.getIsActive())
                .createdAt(service.getCreatedAt())
                .build();
        
        // Fetch clinic name
        clinicRepository.findById(service.getClinicId()).ifPresent(clinic -> 
                response.setClinicName(clinic.getName())
        );
        
        return response;
    }
} 