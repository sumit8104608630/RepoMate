package devPilot.backend.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/debug")
public class DebugController {

    private final java.util.function.Supplier<String> keyPrefixProvider;
    private final EmbeddingModel embeddingModel;
    private final String embeddingModelConfig;
    private final String embeddingFallbackModelConfig;
    private final String chatModel;
    private final String baseUrl;

    public DebugController(
            @Qualifier("openRouterKeyPrefixProvider") java.util.function.Supplier<String> keyPrefixProvider,
            @Qualifier("openRouterEmbeddingModel") EmbeddingModel embeddingModel,
            @Value("${spring.ai.openai.embedding.options.model:liquid/lfm-2.5-embedding-350m:free}") String embeddingModelConfig,
            @Value("${app.ai.embedding.fallback-model:openai/text-embedding-3-small}") String embeddingFallbackModelConfig,
            @Value("${spring.ai.openai.chat.options.model:nvidia/nemotron-3.5-lightning:free}") String chatModel,
            @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl) {
        this.keyPrefixProvider = keyPrefixProvider;
        this.embeddingModel = embeddingModel;
        this.embeddingModelConfig = embeddingModelConfig;
        this.embeddingFallbackModelConfig = embeddingFallbackModelConfig;
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
                : livePrefix.startsWith("sk-or-v1-…")
                        ? "Format OK — if prefix does not match what you see in OpenRouter settings, Render still uses the old cached env var — redeploy/Manual Deploy → Clear build cache."
                        : "KEY FORMAT WARNING: should start with 'sk-or-v1-...' (you may have a bad key)");
        m.put("openrouter_base_url", baseUrl);
        m.put("embedding_primary_model_config", embeddingModelConfig);
        m.put("embedding_fallback_model_config", embeddingFallbackModelConfig);
        m.put("chat_model", chatModel);

        // Detect active model by reflection (best-effort, non-fatal).
        String activeModel = embeddingModelConfig;
        String switchStatus = "Primary model still active; no free-tier daily 429 hit yet.";
        try {
            java.lang.reflect.Field f = embeddingModel.getClass().getDeclaredField("freeTierDailyQuotaExhausted");
            f.setAccessible(true);
            Boolean exhausted = (Boolean) f.get(embeddingModel);
            java.lang.reflect.Field primaryF = embeddingModel.getClass().getDeclaredField("primaryModel");
            primaryF.setAccessible(true);
            java.lang.reflect.Field fallbackF = embeddingModel.getClass().getDeclaredField("fallbackModel");
            fallbackF.setAccessible(true);
            String primary = (String) primaryF.get(embeddingModel);
            String fallback = (String) fallbackF.get(embeddingModel);
            if (Boolean.TRUE.equals(exhausted) && !primary.equals(fallback)) {
                activeModel = fallback;
                switchStatus = "50/day free quota WAS HIT; already PERMANENTLY switched to fallback model: " + fallback;
            } else {
                switchStatus = "Primary '" + primary + "' still active; fallback waiting: '" + fallback + "'";
            }
        } catch (Exception ignored) {
            // Reflection not possible (e.g. wrapped in proxy) → leave defaults.
        }
        m.put("embedding_active_model_currently_in_use", activeModel);
        m.put("embedding_model_switch_status", switchStatus);
        m.put("50_per_day_free_cap_applies_to_any_model_ending_with_:_free", true);
        m.put("how_to_raise_free_cap_from_50_to_1000_per_day_OR_use_fallback_immediately",
                "Option A: Add $10 credits at https://openrouter.ai/settings/credits (raises 50/day -> 1000/day + fallback becomes available via balance). "
                        + "Option B: Change spring.ai.openai.embedding.options.model directly to a paid model (no free cap).");
        return m;
    }
}
