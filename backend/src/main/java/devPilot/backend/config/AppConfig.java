package devPilot.backend.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.web.client.RestClient;

@Configuration
@EnableAsync
public class AppConfig {

    @Value("${app.token-encryptor.password}")
    private String encryptorPassword;

    @Value("${app.token-encryptor.salt}")
    private String encryptorSalt;

    @Bean
    RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean(name = "indexingExecutor")
    Executor indexingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("index-");
        executor.initialize();
        return executor;
    }

    @Bean(name = "streamExecutor")
    Executor streamExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("chat-stream-");
        executor.initialize();
        return executor;
    }

    @Bean
    TextEncryptor tokenEncryptor() {
        return Encryptors.text(encryptorPassword, encryptorSalt);
    }
}
