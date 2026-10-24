package press.mizhifei.dentist.clinic.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Spring Cloud OpenFeign registers one {@code <contextId or name>.FeignClientSpecification}
 * bean per client, and Boot forbids bean overriding, so two clients that share a
 * service name without distinct {@code contextId}s fail application startup.
 */
class FeignClientContextIdTest {

    @Test
    void everyFeignClientHasAUniqueContext() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                        return definition.getMetadata().isInterface();
                    }
                };
        scanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));

        List<String> contexts = scanner.findCandidateComponents("press.mizhifei.dentist.clinic").stream()
                .map(definition -> ((AnnotatedBeanDefinition) definition).getMetadata()
                        .getAnnotations().get(FeignClient.class))
                .map(feign -> {
                    String contextId = feign.getString("contextId");
                    return contextId.isEmpty() ? feign.getString("name") : contextId;
                })
                .toList();

        assertThat(contexts).hasSizeGreaterThanOrEqualTo(3).doesNotHaveDuplicates();
    }
}
