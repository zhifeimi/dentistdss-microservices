package press.mizhifei.dentist.system.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import press.mizhifei.dentist.system.dto.ApiResponse;
import press.mizhifei.dentist.system.dto.SystemSettingRequest;
import press.mizhifei.dentist.system.dto.SystemSettingResponse;
import press.mizhifei.dentist.system.service.SystemSettingService;

import java.util.List;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@RestController
@RequestMapping("/system/setting")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class SystemSettingController {

    private final SystemSettingService service;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<SystemSettingResponse>>> createOrUpdate(
            @RequestBody SystemSettingRequest request) {
        return Mono.fromCallable(() -> {
                    SystemSettingResponse response = service.createOrUpdate(request);
                    return ResponseEntity.ok(ApiResponse.success(response));
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<List<SystemSettingResponse>>>> listAll() {
        return Mono.fromCallable(() ->
                        ResponseEntity.ok(ApiResponse.success(service.listAll())))
                .subscribeOn(Schedulers.boundedElastic());
    }
} 