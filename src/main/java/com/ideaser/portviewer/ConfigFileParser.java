package com.ideaser.portviewer;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.yaml.psi.YAMLDocument;
import org.jetbrains.yaml.psi.YAMLFile;
import org.jetbrains.yaml.psi.YAMLKeyValue;
import org.jetbrains.yaml.psi.YAMLMapping;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses one application or bootstrap config file into a profile to (port, contextPath) map.
 * YAML files are parsed through the bundled YAML plugin PSI (handles multi-document
 * files, quoted values and flow style); .properties files are parsed as plain text.
 */
final class ConfigFileParser {

    /** application / bootstrap, optional -profile suffix, yml/yaml/properties extension. */
    private static final Pattern FILE_NAME =
            Pattern.compile("(application|bootstrap)(?:-([\\w.\\-]+))?\\.(yml|yaml|properties)");

    /** ${VAR} or ${VAR:default} — the default part wins when present. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}:]*:([^}]*)}");

    private ConfigFileParser() {
    }

    record ProfileConfig(@Nullable String portRaw, @Nullable String contextPath,
                         @Nullable String appName) {
    }

    static boolean isConfigFile(@NotNull String fileName) {
        return FILE_NAME.matcher(fileName).matches();
    }

    /**
     * Character offset of a key path (e.g. {@code server.port}, {@code spring.application.name})
     * inside the config file — for click-to-navigate. -1 when the key is absent.
     */
    static int offsetOfKey(@NotNull Project project, @NotNull VirtualFile file, @NotNull String... keyPath) {
        if (file.getName().endsWith(".properties")) {
            return offsetInProperties(file, keyPath);
        }
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (!(psi instanceof YAMLFile yaml)) {
            return -1;
        }
        for (YAMLDocument doc : yaml.getDocuments()) {
            if (!(doc.getTopLevelValue() instanceof YAMLMapping root)) {
                continue;
            }
            YAMLMapping current = root;
            for (int i = 0; i < keyPath.length - 1 && current != null; i++) {
                current = mappingValue(current, keyPath[i]);
            }
            if (current == null) {
                continue;
            }
            for (YAMLKeyValue kv : current.getKeyValues()) {
                if (keyPath[keyPath.length - 1].equals(kv.getKeyText())) {
                    return kv.getTextOffset();
                }
            }
        }
        return -1;
    }

    private static int offsetInProperties(@NotNull VirtualFile file, @NotNull String... keyPath) {
        String key = String.join(".", keyPath);
        try {
            String text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
            int offset = 0;
            for (String line : text.split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith(key + "=") || trimmed.startsWith(key + ":")) {
                    return offset + line.indexOf(key);
                }
                offset += line.length() + 1;
            }
        } catch (IOException e) {
            return -1;
        }
        return -1;
    }

    /** @return profile -> config for that profile; empty when the file defines neither port nor context path. */
    static Map<String, ProfileConfig> parse(@NotNull Project project, @NotNull VirtualFile file) {
        String name = file.getName().toLowerCase(Locale.ROOT);
        Map<String, ProfileConfig> result = new HashMap<>();
        String fileProfile = fileProfile(name);
        if (name.endsWith(".properties")) {
            parseProperties(file, fileProfile, result);
        } else {
            parseYaml(project, file, fileProfile, result);
        }
        return result;
    }

    @Nullable
    private static String fileProfile(@NotNull String lowerCaseName) {
        Matcher m = FILE_NAME.matcher(lowerCaseName);
        if (!m.matches()) {
            return "";
        }
        return m.group(2) == null ? "" : m.group(2);
    }

    // ------------------------------------------------------------------ YAML

    private static void parseYaml(@NotNull Project project,
                                  @NotNull VirtualFile file,
                                  @Nullable String fileProfile,
                                  @NotNull Map<String, ProfileConfig> result) {
        PsiFile psi = PsiManager.getInstance(project).findFile(file);
        if (!(psi instanceof YAMLFile yaml)) {
            return;
        }
        for (YAMLDocument doc : yaml.getDocuments()) {
            if (!(doc.getTopLevelValue() instanceof YAMLMapping root)) {
                continue;
            }
            String profile = firstNonNull(onProfile(root), legacyProfiles(root), fileProfile, "");
            String appName = appName(root);
            String port = null;
            String ctx = null;
            YAMLMapping server = mappingValue(root, "server");
            if (server != null) {
                port = scalarValue(server, "port");
                ctx = scalarValue(server, "context-path"); // Boot 1.x
                if (ctx == null) {
                    YAMLMapping servlet = mappingValue(server, "servlet");
                    if (servlet != null) {
                        ctx = scalarValue(servlet, "context-path"); // Boot 2.x+
                    }
                }
            }
            if (port != null || ctx != null || appName != null) {
                merge(result, profile, port, ctx, appName);
            }
        }
    }

    /** spring.application.name — the service name registered in Nacos/Eureka. */
    @Nullable
    private static String appName(@NotNull YAMLMapping root) {
        YAMLMapping spring = mappingValue(root, "spring");
        if (spring == null) {
            return null;
        }
        YAMLMapping application = mappingValue(spring, "application");
        return application == null ? null : scalarValue(application, "name");
    }

    /** spring.config.activate.on-profile (Boot 2.4+ multi-document blocks). */
    @Nullable
    private static String onProfile(@NotNull YAMLMapping root) {
        YAMLMapping spring = mappingValue(root, "spring");
        if (spring == null) {
            return null;
        }
        YAMLMapping config = mappingValue(spring, "config");
        if (config == null) {
            return null;
        }
        YAMLMapping activate = mappingValue(config, "activate");
        return activate == null ? null : scalarValue(activate, "on-profile");
    }

    /** Legacy spring.profiles (Boot < 2.4 multi-document blocks). */
    @Nullable
    private static String legacyProfiles(@NotNull YAMLMapping root) {
        YAMLMapping spring = mappingValue(root, "spring");
        return spring == null ? null : scalarValue(spring, "profiles");
    }

    @Nullable
    private static YAMLMapping mappingValue(@NotNull YAMLMapping parent, @NotNull String key) {
        for (YAMLKeyValue kv : parent.getKeyValues()) {
            if (key.equals(kv.getKeyText()) && kv.getValue() instanceof YAMLMapping mapping) {
                return mapping;
            }
        }
        return null;
    }

    @Nullable
    private static String scalarValue(@NotNull YAMLMapping parent, @NotNull String key) {
        for (YAMLKeyValue kv : parent.getKeyValues()) {
            if (key.equals(kv.getKeyText())) {
                String value = kv.getValueText();
                return value == null || value.isBlank() ? null : resolvePlaceholder(stripQuotes(value.trim()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------- properties

    private static void parseProperties(@NotNull VirtualFile file,
                                        @Nullable String fileProfile,
                                        @NotNull Map<String, ProfileConfig> result) {
        String text;
        try {
            text = new String(file.contentsToByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return;
        }
        Map<String, String> props = new HashMap<>();
        for (String rawLine : text.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            int sep = separatorIndex(line);
            if (sep <= 0) {
                continue;
            }
            String key = line.substring(0, sep).trim();
            String value = resolvePlaceholder(stripQuotes(line.substring(sep + 1).trim()));
            if (!value.isEmpty()) {
                props.put(key, value);
            }
        }
        String port = props.get("server.port");
        String ctx = props.containsKey("server.servlet.context-path")
                ? props.get("server.servlet.context-path")
                : props.get("server.context-path");
        String appName = props.get("spring.application.name");
        if (port != null || ctx != null || appName != null) {
            merge(result, fileProfile == null ? "" : fileProfile, port, ctx, appName);
        }
    }

    /** Index of the first unescaped '=' or ':' separator, -1 when absent. */
    private static int separatorIndex(@NotNull String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\\') {
                i++;
                continue;
            }
            if (c == '=' || c == ':') {
                return i;
            }
        }
        return -1;
    }

    // ----------------------------------------------------------------- shared

    /** Later documents/files override same-profile values defined by earlier ones. */
    private static void merge(@NotNull Map<String, ProfileConfig> result,
                              @NotNull String profile,
                              @Nullable String port,
                              @Nullable String ctx,
                              @Nullable String appName) {
        result.merge(profile, new ProfileConfig(port, ctx, appName),
                (oldCfg, newCfg) -> new ProfileConfig(
                        newCfg.portRaw() != null ? newCfg.portRaw() : oldCfg.portRaw(),
                        newCfg.contextPath() != null ? newCfg.contextPath() : oldCfg.contextPath(),
                        newCfg.appName() != null ? newCfg.appName() : oldCfg.appName()));
    }

    @Nullable
    private static String resolvePlaceholder(@NotNull String value) {
        Matcher m = PLACEHOLDER.matcher(value);
        if (m.matches()) {
            String fallback = m.group(1).trim();
            return fallback.isEmpty() ? value : stripQuotes(fallback);
        }
        return value;
    }

    @NotNull
    private static String stripQuotes(@NotNull String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    @SafeVarargs
    @Nullable
    private static <T> T firstNonNull(@Nullable T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }
}
