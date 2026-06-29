package press.mizhifei.dentist.admin;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationYamlTest {

    private static final List<String> CONFIGURATIONS = List.of(
            "application.yml",
            "application-docker.yml",
            "application-prod.yml");

    @Test
    void reactiveAdminBasePathIsConfiguredForEveryRuntimeProfile() throws IOException {
        var loader = new YamlPropertySourceLoader();

        for (String configuration : CONFIGURATIONS) {
            List<PropertySource<?>> sources =
                    loader.load(configuration, new ClassPathResource(configuration));

            assertThat(sources)
                    .as(configuration)
                    .hasSize(1);
            assertThat(sources.getFirst().getProperty("spring.webflux.base-path"))
                    .as(configuration)
                    .isEqualTo("/admin");
        }
    }
}
