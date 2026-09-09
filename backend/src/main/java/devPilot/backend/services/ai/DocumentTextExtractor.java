package devPilot.backend.services.ai;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;

/**
 * Robust text extraction for Spring AI 2.0.0 {@link Document} objects.
 *
 * <p>Spring AI 2.0.0 changes the Document API and PgVectorStore may populate
 * the returned Document via reflection or constructor args that don't line up
 * with the public getter names (same class of issue as Message.getContent()).
 * This helper tries every known access path, and if all fail, runs a full
 * reflective field-dump (logged once per JVM session) so operators can see
 * exactly which field the content is stored in on their build.</p>
 */
public final class DocumentTextExtractor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTextExtractor.class);
    private static volatile boolean dumpLogged = false;

    private DocumentTextExtractor() {}

    public static String extract(Document doc) {
        if (doc == null) return "";

        // 1) Public getters in priority order
        try { String t = doc.getText(); if (t != null && !t.isBlank()) return t; } catch (Exception ignored) {}
        try { Object c = doc.getClass().getMethod("getContent").invoke(doc); if (nonBlank(c)) return c.toString(); } catch (Exception ignored) {}
        try { Object c = doc.getClass().getMethod("content").invoke(doc); if (nonBlank(c)) return c.toString(); } catch (Exception ignored) {}

        // 2) Common field names on Document itself
        for (String fieldName : new String[]{"text", "content", "document", "pageContent", "page_content", "rawText"}) {
            String v = readField(doc, doc.getClass(), fieldName);
            if (v != null && !v.isBlank()) return v;
        }

        // 3) Walk superclass chain (Document may extend something with content)
        Class<?> clz = doc.getClass().getSuperclass();
        while (clz != null && clz != Object.class) {
            for (String fieldName : new String[]{"text", "content", "document", "pageContent", "page_content"}) {
                String v = readField(doc, clz, fieldName);
                if (v != null && !v.isBlank()) return v;
            }
            clz = clz.getSuperclass();
        }

        // 4) toString() — many record/class toString() renders the content inline
        try {
            String ts = doc.toString();
            if (ts != null && ts.length() > 60 && !ts.contains("content=<null>") && !ts.contains("text=<null>")) {
                // Heuristic: only accept if it looks like real text (length + not null placeholders)
                // Not ideal but better than losing content entirely.
                // We won't return toString() blindly — instead fall through to dump.
            }
        } catch (Exception ignored) {}

        // 5) Once-per-session diagnostic dump: log ALL declared fields with
        //    their (truncated) values so we can SEE where content really lives.
        if (!dumpLogged) {
            synchronized (DocumentTextExtractor.class) {
                if (!dumpLogged) {
                    dumpLogged = true;
                    log.warn("DocumentTextExtractor: getAllKnownPaths returned empty for Document of type {}. Dumping ALL fields:",
                            doc.getClass().getName());
                    dumpAll(doc);
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------- helpers
    private static boolean nonBlank(Object o) {
        return o != null && !o.toString().isBlank();
    }

    private static String readField(Object target, Class<?> declaringClz, String fieldName) {
        try {
            Field f = declaringClz.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object v = f.get(target);
            return v == null ? null : v.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void dumpAll(Object doc) {
        List<String> lines = new ArrayList<>();
        Class<?> clz = doc.getClass();
        while (clz != null && clz != Object.class) {
            for (Field f : clz.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object v = f.get(doc);
                    String s = v == null ? "<null>" : v.toString();
                    if (s.length() > 400) s = s.substring(0, 400) + "…[truncated total=" + s.length() + "]";
                    lines.add(clz.getSimpleName() + "." + f.getName() + " (" + f.getType().getSimpleName() + ") = " + s.replace('\n', ' '));
                } catch (Exception ex) {
                    lines.add(clz.getSimpleName() + "." + f.getName() + " → ERROR " + ex);
                }
            }
            clz = clz.getSuperclass();
        }
        for (String line : lines) {
            log.warn("  DUMP: {}", line);
        }
    }
}
