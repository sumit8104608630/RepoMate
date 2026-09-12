package devPilot.backend.config;

import com.knuddels.jtokkit.api.EncodingType;

import org.springframework.ai.embedding.BatchingStrategy;
import org.springframework.ai.embedding.TokenCountBatchingStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingConfig {

    @Bean
    public BatchingStrategy batchingStrategy() {
        return new TokenCountBatchingStrategy(EncodingType.CL100K_BASE, 1000, 0.1);
    }
}