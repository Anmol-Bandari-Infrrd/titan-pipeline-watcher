package ai.infrrd.idc.watcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.mongo.MongoAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Mongo auto-config is off: clients are built per environment by EnvRegistry. */
@EnableScheduling
@ConfigurationPropertiesScan
@SpringBootApplication(exclude = MongoAutoConfiguration.class)
public class PipelineWatcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(PipelineWatcherApplication.class, args);
    }
}
