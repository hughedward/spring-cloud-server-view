package com.ideaser.portviewer;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans all modules of a project, finds their Spring config files under
 * {@code src/main/resources} (or any main source root) and resolves, per profile,
 * the port and context path Spring Boot would use.
 */
public final class PortScanner {

    private static final Comparator<ModulePortInfo> ORDER =
            Comparator.comparing(ModulePortInfo::moduleName)
                    .thenComparing(info -> info.profile().isEmpty() ? "" : info.profile());

    private PortScanner() {
    }

    @NotNull
    public static List<ModulePortInfo> scan(@NotNull Project project) {
        Map<String, List<RunConfigReader.OverrideInfo>> runOverrides = RunConfigReader.readOverrides(project);
        List<ModulePortInfo> rows = new ArrayList<>();
        for (Module module : ModuleManager.getInstance(project).getModules()) {
            collectModule(module, runOverrides, rows);
        }
        rows.sort(ORDER);
        return rows;
    }

    private static void collectModule(@NotNull Module module,
                                      @NotNull Map<String, List<RunConfigReader.OverrideInfo>> runOverrides,
                                      @NotNull List<ModulePortInfo> out) {
        VirtualFile moduleDir = firstContentRoot(module);
        VirtualFile buildFile = findBuildFile(module);
        String buildFileName = buildFile == null ? null : buildFile.getName();

        Map<String, ModulePortInfo> byProfile = new LinkedHashMap<>();
        List<VirtualFile> files = findConfigFiles(module);
        if (files.isEmpty()) {
            // no config files: still a Spring Boot service when its sources declare
            // @SpringBootApplication (runs on the implicit default 8080)
            if (hasSpringBootApplication(module)) {
                byProfile.put("", new ModulePortInfo(module.getName(), null, "",
                        ModulePortInfo.SPRING_BOOT_DEFAULT_PORT, true, null, null,
                        "主类检测", null, moduleDir, buildFile, buildFileName));
            } else {
                return; // parent aggregator / shared lib — skip
            }
        } else {
            // Spring Boot precedence for identical locations: application > bootstrap,
            // .properties > .yml/.yaml — sort best-first, first writer wins, gaps filled later.
            files.sort(Comparator
                    .comparingInt((VirtualFile f) -> f.getName().startsWith("bootstrap") ? 1 : 0)
                    .thenComparingInt(f -> f.getName().endsWith(".properties") ? 0 : 1));
            for (VirtualFile file : files) {
                for (Map.Entry<String, ConfigFileParser.ProfileConfig> entry
                        : ConfigFileParser.parse(module.getProject(), file).entrySet()) {
                    byProfile.merge(entry.getKey(),
                            toInfo(module.getName(), entry.getKey(), entry.getValue(), file,
                                    moduleDir, buildFile, buildFileName),
                            PortScanner::keepFirstFillGaps);
                }
            }
        }

        // every run configuration with an explicit port is its own live instance:
        // a module regularly runs on 8010 (yml) AND 8011 (-Dserver.port) side by side
        ModulePortInfo defaultRow = byProfile.get("");
        Set<Integer> portsInUse = new HashSet<>();
        for (ModulePortInfo row : byProfile.values()) {
            if (row.port() != null) {
                portsInUse.add(row.port());
            }
        }
        for (RunConfigReader.OverrideInfo override : runOverrides.getOrDefault(module.getName(), List.of())) {
            if (!portsInUse.add(override.port())) {
                continue; // same port as an existing row — no duplicate
            }
            byProfile.put("\u0000run:" + override.configName(), new ModulePortInfo(
                    module.getName(),
                    defaultRow == null ? null : defaultRow.appName(),
                    "", override.port(), false, String.valueOf(override.port()),
                    defaultRow == null ? null : defaultRow.contextPath(),
                    override.configName(), null,
                    moduleDir, buildFile, buildFileName));
        }
        out.addAll(byProfile.values());
    }

    /** True when any main-source java file declares @SpringBootApplication. */
    private static boolean hasSpringBootApplication(@NotNull Module module) {
        for (VirtualFile sourceRoot : ModuleRootManager.getInstance(module).getSourceRoots()) {
            if (!sourceRoot.getPath().contains("/main/")) {
                continue;
            }
            if (containsBootAnnotation(sourceRoot)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsBootAnnotation(@NotNull VirtualFile dir) {
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isValid()) {
                continue;
            }
            if (child.isDirectory()) {
                if (containsBootAnnotation(child)) {
                    return true;
                }
            } else if (child.getName().endsWith(".java") && fileTextContains(child, "@SpringBootApplication")) {
                return true;
            }
        }
        return false;
    }

    private static boolean fileTextContains(@NotNull VirtualFile file, @NotNull String needle) {
        try {
            return new String(file.contentsToByteArray(), java.nio.charset.StandardCharsets.UTF_8).contains(needle);
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static ModulePortInfo toInfo(@NotNull String moduleName,
                                         @NotNull String profile,
                                         @NotNull ConfigFileParser.ProfileConfig cfg,
                                         @NotNull VirtualFile file,
                                         @Nullable VirtualFile moduleDir,
                                         @Nullable VirtualFile buildFile,
                                         @Nullable String buildFileName) {
        boolean isDefault = cfg.portRaw() == null;
        Integer port = isDefault ? ModulePortInfo.SPRING_BOOT_DEFAULT_PORT : parsePort(cfg.portRaw());
        return new ModulePortInfo(moduleName, cfg.appName(), profile, port, isDefault, cfg.portRaw(),
                cfg.contextPath(), file.getName(), file, moduleDir, buildFile, buildFileName);
    }

    /** Higher-priority row was merged first; the new row may only fill null fields. */
    private static ModulePortInfo keepFirstFillGaps(@NotNull ModulePortInfo first, @NotNull ModulePortInfo second) {
        boolean firstHasPort = first.portRaw() != null;
        String portRaw = firstHasPort ? first.portRaw() : second.portRaw();
        boolean defaultPort = portRaw == null;
        Integer port = firstHasPort ? first.port() : second.port();
        if (!firstHasPort && second.portRaw() != null) {
            port = parsePort(second.portRaw());
        } else if (defaultPort) {
            port = ModulePortInfo.SPRING_BOOT_DEFAULT_PORT;
        }
        String appName = first.appName() != null ? first.appName() : second.appName();
        String contextPath = first.contextPath() != null ? first.contextPath() : second.contextPath();
        // the port-providing file is the one shown; fall back to the context-path file
        boolean takeSecondFile = !firstHasPort && second.portRaw() != null;
        return new ModulePortInfo(first.moduleName(), appName, first.profile(), port, defaultPort, portRaw,
                contextPath,
                takeSecondFile ? second.configFileName() : first.configFileName(),
                takeSecondFile ? second.configFile() : first.configFile(),
                first.moduleDir(), first.buildFile(), first.buildFileName());
    }

    /** Numeric ports only; placeholders like {@code ${SERVER_PORT}} resolve to null. */
    private static Integer parsePort(@NotNull String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<VirtualFile> findConfigFiles(@NotNull Module module) {
        Set<VirtualFile> result = new LinkedHashSet<>();
        ModuleRootManager roots = ModuleRootManager.getInstance(module);
        for (VirtualFile sourceRoot : roots.getSourceRoots()) {
            if (!sourceRoot.getPath().contains("/main/")) {
                continue; // skip test roots
            }
            collectConfigChildren(sourceRoot, result);
        }
        for (VirtualFile contentRoot : roots.getContentRoots()) {
            VirtualFile resources = contentRoot.findChild("src/main/resources");
            if (resources != null) {
                collectConfigChildren(resources, result);
            }
        }
        return new ArrayList<>(result);
    }

    private static void collectConfigChildren(@NotNull VirtualFile dir, @NotNull Set<VirtualFile> out) {
        for (VirtualFile child : dir.getChildren()) {
            if (!child.isDirectory() && ConfigFileParser.isConfigFile(child.getName())) {
                out.add(child);
            }
        }
    }

    /** First content root — the module's own directory in standard Maven/Gradle layouts. */
    @Nullable
    private static VirtualFile firstContentRoot(@NotNull Module module) {
        VirtualFile[] roots = ModuleRootManager.getInstance(module).getContentRoots();
        return roots.length == 0 ? null : roots[0];
    }

    /** pom.xml first (Maven), then build.gradle.kts / build.gradle (Gradle). */
    @Nullable
    private static VirtualFile findBuildFile(@NotNull Module module) {
        for (VirtualFile root : ModuleRootManager.getInstance(module).getContentRoots()) {
            for (String name : new String[]{"pom.xml", "build.gradle.kts", "build.gradle"}) {
                VirtualFile child = root.findChild(name);
                if (child != null) {
                    return child;
                }
            }
        }
        return null;
    }
}
