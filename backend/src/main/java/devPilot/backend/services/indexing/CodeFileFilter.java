package devPilot.backend.services.indexing;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

@Component
public class CodeFileFilter {
    private static final Set<String> SKIP_DIR_PARTS = Set.of(
            "node_modules",
            ".git",
            "dist",
            "build",
            "target",
            ".next",
            "vendor",
            "__pycache__",
            ".idea",
            ".vscode",
            "coverage",
            "out");

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala",
            "ts", "tsx", "js", "jsx", "mjs", "cjs",
            "py", "go", "rs", "rb", "php",
            "c", "h", "cpp", "hpp", "cs",
            "swift", "m", "mm",
            "md", "mdx", "txt",
            "yml", "yaml", "json", "toml", "xml",
            "properties", "gradle", "sql",
            "sh", "bash", "zsh",
            "dockerfile", "makefile",
            "html", "css", "scss", "sass",
            "vue", "svelte");

    private static final Set<String> SKIP_FILENAMES = Set.of(
            "package-lock.json",
            "yarn.lock",
            "pnpm-lock.yaml",
            "composer.lock",
            "cargo.lock",
            "poetry.lock");

    private static final Map<String, String> COMMENT_PREFIXES = Map.ofEntries(
            Map.entry("java", "//"),
            Map.entry("kt", "//"),
            Map.entry("kts", "//"),
            Map.entry("scala", "//"),
            Map.entry("ts", "//"),
            Map.entry("tsx", "//"),
            Map.entry("js", "//"),
            Map.entry("jsx", "//"),
            Map.entry("mjs", "//"),
            Map.entry("cjs", "//"),
            Map.entry("go", "//"),
            Map.entry("rs", "//"),
            Map.entry("php", "//"),
            Map.entry("c", "//"),
            Map.entry("h", "//"),
            Map.entry("cpp", "//"),
            Map.entry("hpp", "//"),
            Map.entry("cs", "//"),
            Map.entry("swift", "//"),
            Map.entry("m", "//"),
            Map.entry("mm", "//"),
            Map.entry("vue", "//"),
            Map.entry("svelte", "//"),
            Map.entry("py", "#"),
            Map.entry("rb", "#"),
            Map.entry("yml", "#"),
            Map.entry("yaml", "#"),
            Map.entry("toml", "#"),
            Map.entry("sh", "#"),
            Map.entry("bash", "#"),
            Map.entry("zsh", "#"),
            Map.entry("dockerfile", "#"),
            Map.entry("makefile", "#"),
            Map.entry("properties", "#"),
            Map.entry("gradle", "//"),
            Map.entry("sql", "--"),
            Map.entry("xml", "<!--"),
            Map.entry("html", "<!--"),
            Map.entry("md", "<!--"),
            Map.entry("mdx", "<!--"),
            Map.entry("css", "/*"),
            Map.entry("scss", "/*"),
            Map.entry("sass", "/*"),
            Map.entry("txt", "#"),
            Map.entry("json", "//"));
// backend\src\main\java\devPilot\backend\repository\filename.java
    public boolean isEligible(String path, long sizeBytes, long maxFileBytes) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);

        for (String part : lower.split("/")) {
            if (SKIP_DIR_PARTS.contains(part)) {
                return false;
            }
        }

        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if (SKIP_FILENAMES.contains(fileName)) {
            return false;
        }
        if (fileName.startsWith(".")) {
            return false;
        }
        if (sizeBytes > maxFileBytes) {
            return false;
        }

        if ("dockerfile".equals(fileName) || "makefile".equals(fileName)) {
            return true;
        }

        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        String ext = fileName.substring(dot + 1);
        return ALLOWED_EXTENSIONS.contains(ext);
    }

    public String detectLanguage(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String fileName = lower.substring(lower.lastIndexOf('/') + 1);
        if ("dockerfile".equals(fileName)) {
            return "dockerfile";
        }
        if ("makefile".equals(fileName)) {
            return "makefile";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        return fileName.substring(dot + 1);
    }

    public String commentPrefixFor(String language) {
        if (language == null) {
            return "//";
        }
        return COMMENT_PREFIXES.getOrDefault(language.toLowerCase(Locale.ROOT), "//");
    }
}