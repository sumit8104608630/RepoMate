package devPilot.backend.config;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class KeepAliveScheduler {

    private static final Logger log = LoggerFactory.getLogger(KeepAliveScheduler.class);

    private final RestClient restClient;
    private final String healthUrl;
    private final boolean enabled;

    public KeepAliveScheduler(
            @Value("${server.port:8081}") int serverPort,
            @Value("${app.keepalive.self-ping.url:}") String explicitUrl,
            @Value("${app.keepalive.enabled:true}") boolean enabled) {
        this.restClient = RestClient.builder().build();
        this.healthUrl = !explicitUrl.isBlank()
                ? explicitUrl
                : "http://localhost:" + serverPort + "/api/health";
        this.enabled = enabled;
        log.info("KeepAliveScheduler: enabled={}, targetUrl={}", enabled, healthUrl);
    }

    @Scheduled(fixedRate = 10 * 60 * 1000, initialDelay = 60 * 1000)
    public void pingSelf() {
        if (!enabled) return;
        try {
            String body = restClient.get()
                    .uri(URI.create(healthUrl))
                    .retrieve()
                    .body(String.class);
            log.debug("Keepalive self-ping OK: {}", body == null ? "<empty>" : body.replace('\n', ' '));
        } catch (Exception ex) {
            log.warn("Keepalive self-ping failed ({}): {}", healthUrl, ex.toString());
        }
    }
}
