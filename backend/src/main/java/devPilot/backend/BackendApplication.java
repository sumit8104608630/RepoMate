package devPilot.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(excludeName = {
    "org.springframework.ai.transformers.autoconfigure.TransformersEmbeddingAutoConfiguration",
    "org.springframework.ai.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration",
    "org.springframework.ai.openai.autoconfigure.OpenAiChatAutoConfiguration"
})
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
