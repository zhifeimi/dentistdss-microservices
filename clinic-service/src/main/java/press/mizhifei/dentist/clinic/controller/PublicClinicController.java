package press.mizhifei.dentist.clinic.controller;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import press.mizhifei.dentist.clinic.directory.PublicClinicDirectoryService;
import press.mizhifei.dentist.clinic.directory.PublicDirectoryProperties;
import press.mizhifei.dentist.clinic.dto.ApiResponse;

import java.net.URI;

/**
 * Public, read-only clinic directory for the frontend's build-time prerender
 * of the clinic and suburb pages. Exposed through the gateway as
 * {@code GET /api/public/clinics} and {@code GET /api/public/clinics/{slug}}.
 *
 * <p>Responses carry {@code Cache-Control: public, max-age=300} and a weak
 * ETag over the clinic data ({@code generatedAt} excluded); a matching
 * {@code If-None-Match} is answered with 304 by Spring MVC.
 */
@RestController
@RequestMapping("/public/clinics")
public class PublicClinicController {

    private final PublicClinicDirectoryService directoryService;
    private final PublicDirectoryProperties properties;

    public PublicClinicController(
            PublicClinicDirectoryService directoryService,
            PublicDirectoryProperties properties) {
        this.directoryService = directoryService;
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<?> listClinics() {
        PublicClinicDirectoryService.Directory directory = directoryService.listClinics();
        return ResponseEntity.ok()
                .cacheControl(cacheControl())
                .eTag(weakETag(directory.fingerprint()))
                .body(directory.response());
    }

    @GetMapping("/{slug}")
    public ResponseEntity<?> getClinic(@PathVariable String slug) {
        return switch (directoryService.findBySlug(slug)) {
            case PublicClinicDirectoryService.Found found -> ResponseEntity.ok()
                    .cacheControl(cacheControl())
                    .eTag(weakETag(found.fingerprint()))
                    .body(found.clinic());
            case PublicClinicDirectoryService.MovedPermanently moved -> ResponseEntity
                    .status(HttpStatus.MOVED_PERMANENTLY)
                    .cacheControl(cacheControl())
                    .location(URI.create(properties.apiBasePath() + "/" + moved.currentSlug()))
                    .build();
            case PublicClinicDirectoryService.NotFound notFound -> ResponseEntity
                    .status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.error("Clinic not found"));
        };
    }

    private CacheControl cacheControl() {
        return CacheControl.maxAge(properties.cacheMaxAge()).cachePublic();
    }

    /**
     * Weak: two responses with the same clinic data differ in
     * {@code generatedAt}, so they are equivalent but not byte-identical.
     */
    private static String weakETag(String fingerprint) {
        return "W/\"" + fingerprint + "\"";
    }
}
