package devPilot.backend.config;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

import org.apache.tomcat.util.http.Rfc6265CookieProcessor;

@Configuration
@EnableAsync
public class AppConfig {
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
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("chatstream-");
        executor.initialize();
        return executor;
    }

    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> cookieSameSiteCustomizer(
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        return factory -> factory.addContextCustomizers(context -> {
            Rfc6265CookieProcessor processor = new Rfc6265CookieProcessor();
            processor.setSameSiteCookies(sameSite.toUpperCase());
            context.setCookieProcessor(processor);
        });
    }
}