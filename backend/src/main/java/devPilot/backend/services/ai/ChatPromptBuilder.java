package devPilot.backend.services.ai;

import org.springframework.stereotype.Component;

/**
 * Builds the prompts sent to OpenAI.
 *
 * <p>We use two messages:
 * <ul>
 *   <li><b>System</b> — rules for how the assistant should behave</li>
 *   <li><b>User</b> — retrieved code context + the actual question</li>
 * </ul>
 */
@Component
public class ChatPromptBuilder {

    public String systemPrompt(String repositoryFullName) {
        return """
                You are RepoMate, an expert assistant for the %s codebase.
                Answer using ONLY the provided code context.
                If the question is not related to the codebase, or if the provided context is insufficient to answer, answer exactly and verbatim: "it is out of domain"
                Never say "I am unsure" or "I don't know" — use the exact phrase above for off-topic or unanswerable questions.
                Cite file paths and line ranges when relevant.
                Be concise and technical.
                """.formatted(repositoryFullName);
    }

    public String userPrompt(String codeContext, String question) {
        return """
                Code context:
                %s

                User question:
                %s
                """.formatted(codeContext, question);
    }
}