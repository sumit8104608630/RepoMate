package devPilot.backend.services.ai;

import org.springframework.ai.document.Document;

/**
 * Robust text extraction for Spring AI 2.0.0 {@link Document} objects.
 *
 * <p>Spring AI 2.0.0 renames/breaks some accessors (same class of issue as
 * {@code Message.getContent()} noted in project memory). PgVectorStore may
 * populate the Document via reflection onto fields but the public getter can
 * return empty string. This helper tries every possible access path.</p>
 */
public final class DocumentTextExtractor {

    private DocumentTextExtractor() {}

    public static String extract(Document doc) {
        if (doc == null) return "";

        try {
            String t = doc.getText();
            if (t != null && !t.isBlank()) return t;
        } catch (Exception ignored) {}

        try {
            Object c = doc.getClass().getMethod("getContent").invoke(doc);
            if (c != null && !c.toString().isBlank()) return c.toString();
        } catch (Exception ignored) {}

        String[] fieldNames = {"text", "content", "document"};
        for (String fieldName : fieldNames) {
            try {
                java.lang.reflect.Field f = doc.getClass().getDeclaredField(fieldName);
                f.setAccessible(true);
                Object v = f.get(doc);
                if (v != null && !v.toString().isBlank()) return v.toString();
            } catch (Exception ignored) {}
        }

        try {
            java.lang.reflect.Field f = doc.getClass().getSuperclass().getDeclaredField("content");
            f.setAccessible(true);
            Object v = f.get(doc);
            if (v != null && !v.toString().isBlank()) return v.toString();
        } catch (Exception ignored) {}

        return "";
    }
}
