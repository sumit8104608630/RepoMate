package devPilot.backend.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final java.util.function.Supplier<String> keyPrefixProvider;
    private final String embeddingModel;
    private final String chatModel;
    private final String baseUrl;

    public DebugController(
            @Qualifier("openRouterKeyPrefixProvider") java.util.function.Supplier<String> keyPrefixProvider,
            @Value("${spring.ai.openai.embedding.options.model:liquid/lfm-2.5-embedding-350m:free}") String embeddingModel,
            @Value("${spring.ai.openai.chat.options.model:nvidia/nemotron-3.5-lightning:free}") String chatModel,
            @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl) {
        this.keyPrefixProvider = keyPrefixProvider;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/ai-key-prefix")
    public Map<String, Object> aiKeyPrefix() {
        Map<String, Object> m = new LinkedHashMap<>();
        String livePrefix = keyPrefixProvider.get();
        m.put("ok", true);
        m.put("live_key_prefix", livePrefix);
        m.put("expected_key_prefix_hint", "Compare to the first 10 chars of the key you created at openrouter.ai/settings/keys");
        m.put("match_status", livePrefix.equals("<EMPTY>")
                ? "KEY IS EMPTY! Set env OPENROUTER_API_KEY on Render."
                : livePrefix.startsWith("sk-or-v1-…") ? "Format OK — if prefix does not match what you see in OpenRouter settings, Render still uses the old cached env var — redeploy/Manual Deploy → Clear build cache."
                        : "KEY FORMAT WARNING: should start with 'sk-or-v1-...' (you may have a bad key)");
        m.put("openrouter_base_url", baseUrl);
        m.put("embedding_model", embeddingModel);
        m.put("chat_model", chatModel);
        m.put("50_per_day_free_cap_applies_if_model_ends_with_:_free", true);
        m.put("how_to_raise_free_cap_from_50_to_1000_per_day", "Add $10 credits at https://openrouter.ai/settings/credits");
        return m;
    }
}
