package press.mizhifei.dentist.clinicalrecords.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.mongodb.client.gridfs.GridFSBucket;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration;
import org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Boot 4 moved the database name from spring.data.mongodb.database to
 * spring.mongodb.database; the GridFS buckets must follow whatever database
 * Boot resolves. No server is needed: the driver connects lazily.
 */
class MongoConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MongoAutoConfiguration.class, DataMongoAutoConfiguration.class))
            .withUserConfiguration(MongoConfig.class)
            .withPropertyValues("spring.mongodb.uri=mongodb://localhost:27017", "spring.mongodb.database=records_db");

    @Test
    void gridFsBucketsUseTheConfiguredDatabase() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean("gridFSBucket", GridFSBucket.class).getBucketName()).isEqualTo("fs");
            assertThat(context.getBean("thumbnailGridFSBucket", GridFSBucket.class).getBucketName())
                    .isEqualTo("thumbnails");
            assertThat(context.getBean(MongoTemplate.class).getDb().getName())
                    .isEqualTo("records_db");
        });
    }
}
