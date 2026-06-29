package press.mizhifei.dentist.appointment;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThatCode;

class ConfigurationYamlTest {

    private static final List<String> CONFIGURATIONS = List.of(
            "application.yml",
            "application-docker.yml",
            "application-prod.yml");

    @Test
    void configurationFilesContainValidYamlMappings() {
        var loader = new YamlPropertySourceLoader();

        for (String configuration : CONFIGURATIONS) {
            var resource = new ClassPathResource(configuration);
            assertThatCode(() -> loader.load(configuration, resource))
                    .as(configuration)
                    .doesNotThrowAnyException();
        }
    }
}
