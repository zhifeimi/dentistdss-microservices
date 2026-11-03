package press.mizhifei.dentist.appointment;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.logging.LogFile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ConfigurationYamlTest {

    private static final List<String> CONFIGURATIONS = List.of(
            "application.yml",
            "application-docker.yml",
            "application-prod.yml");

    @Test
    void configurationFilesUseConsoleLoggingOnly() throws IOException {
        var loader = new YamlPropertySourceLoader();

        for (String configuration : CONFIGURATIONS) {
            var environment = new MockEnvironment();
            loader.load(configuration, new ClassPathResource(configuration))
                    .forEach(environment.getPropertySources()::addFirst);

            assertThat(LogFile.get(environment))
                    .as("%s must not require a writable log directory", configuration)
                    .isNull();
        }
    }

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
