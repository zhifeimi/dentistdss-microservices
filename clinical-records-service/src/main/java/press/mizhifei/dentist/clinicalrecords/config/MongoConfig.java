package press.mizhifei.dentist.clinicalrecords.config;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 *
 * @author zhifeimi
 * @email zm377@uowmail.edu.au
 * @github https://github.com/zm377
 *
 */
@Configuration
public class MongoConfig {

    // Boot's MongoTemplate already resolves the database (spring.mongodb.database
    // or the URI's path), so the buckets never re-read a renamed property.
    @Bean
    public GridFSBucket gridFSBucket(MongoTemplate mongoTemplate) {
        return GridFSBuckets.create(mongoTemplate.getDb());
    }

    @Bean
    public GridFSBucket thumbnailGridFSBucket(MongoTemplate mongoTemplate) {
        return GridFSBuckets.create(mongoTemplate.getDb(), "thumbnails");
    }
}
