package com.ideaser.portviewer;

import com.intellij.execution.RunManager;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.project.Project;
import org.jdom.Element;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads {@code -Dserver.port=...} / {@code --server.port=...} from run configurations.
 * Works for any configuration type (Spring Boot, Application, ...) by dumping the
 * configuration's externalized XML, so no dependency on the Spring plugin's run
 * configuration classes is needed.
 */
final class RunConfigReader {

    /** matches -Dserver.port=8011, -DServer.port=8011, --server.port=8011 */
    private static final Pattern PORT_OVERRIDE = Pattern.compile("(?i)server\\.port\\s*=\\s*(\\d+)");

    private RunConfigReader() {
    }

    /** One run configuration that starts the module on an explicit port. */
    record OverrideInfo(int port, @NotNull String configName) {
    }

    /** @return module name to its port-carrying run configurations (may be several — one per instance) */
    @NotNull
    static Map<String, List<OverrideInfo>> readOverrides(@NotNull Project project) {
        Map<String, List<OverrideInfo>> result = new HashMap<>();
        for (RunConfiguration configuration : RunManager.getInstance(project).getAllConfigurationsList()) {
            Element xml = new Element("configuration");
            try {
                configuration.writeExternal(xml);
            } catch (Exception e) {
                continue; // exotic configuration types may fail to serialize — skip them
            }
            String moduleName = moduleName(xml);
            Integer port = portOverride(xml);
            if (moduleName != null && port != null) {
                result.computeIfAbsent(moduleName, key -> new ArrayList<>())
                        .add(new OverrideInfo(port, configuration.getName()));
            }
        }
        return result;
    }

    @Nullable
    private static String moduleName(@NotNull Element configurationXml) {
        Element module = configurationXml.getChild("module");
        return module == null ? null : module.getAttributeValue("name");
    }

    @Nullable
    private static Integer portOverride(@NotNull Element configurationXml) {
        String candidates = optionValue(configurationXml, "VM_PARAMETERS")
                + " " + optionValue(configurationXml, "PROGRAM_PARAMETERS");
        Matcher matcher = PORT_OVERRIDE.matcher(candidates);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Integer.valueOf(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    private static String optionValue(@NotNull Element configurationXml, @NotNull String optionName) {
        for (Element option : configurationXml.getChildren("option")) {
            if (optionName.equals(option.getAttributeValue("name"))) {
                return option.getAttributeValue("value");
            }
        }
        return null;
    }

    /** All run configuration names belonging to the module, in RunManager order. */
    @NotNull
    static List<String> configNamesForModule(@NotNull Project project, @NotNull String moduleName) {
        List<String> names = new ArrayList<>();
        for (RunConfiguration configuration : RunManager.getInstance(project).getAllConfigurationsList()) {
            Element xml = new Element("configuration");
            try {
                configuration.writeExternal(xml);
            } catch (Exception e) {
                continue; // exotic configuration types may fail to serialize — skip them
            }
            if (moduleName.equals(moduleName(xml))) {
                names.add(configuration.getName());
            }
        }
        return names;
    }
}
