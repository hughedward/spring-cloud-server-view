package com.ideaser.portviewer;

import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

/**
 * One resolved row: the port one concrete instance of a module/profile would use.
 * Run-configuration instances ({@code -Dserver.port}) get their own rows.
 *
 * @param moduleName     IDEA module name (subproject)
 * @param appName        spring.application.name (registry service name)
 * @param profile        profile name, empty string = default profile
 * @param port           resolved numeric port, null when the raw value is an
 *                       unresolved placeholder like {@code ${SERVER_PORT}}
 * @param defaultPort    true when no server.port was configured anywhere and
 *                       Spring Boot's implicit 8080 is assumed
 * @param portRaw        raw configured value, null when nothing configured
 * @param contextPath    server.servlet.context-path (or legacy server.context-path)
 * @param configFileName name of the file the port was taken from, or the run configuration name
 * @param configFile     that file (null for run-configuration rows)
 * @param moduleDir      module content root — clicking the module name reveals it in the Project view
 * @param buildFile      pom.xml / build.gradle(.kts) of the module, for one-click open
 * @param buildFileName  display name, kept for when the file gets invalidated
 * @param runConfigName  run configuration this row was derived from (run-config rows only);
 *                       the start/stop/restart actions target it
 */
public record ModulePortInfo(
        String moduleName,
        @Nullable String appName,
        String profile,
        @Nullable Integer port,
        boolean defaultPort,
        @Nullable String portRaw,
        @Nullable String contextPath,
        String configFileName,
        @Nullable VirtualFile configFile,
        @Nullable VirtualFile moduleDir,
        @Nullable VirtualFile buildFile,
        @Nullable String buildFileName,
        @Nullable String runConfigName) {

    public static final int SPRING_BOOT_DEFAULT_PORT = 8080;

    public String displayProfile() {
        return profile.isEmpty() ? "default" : profile;
    }

    public String displayPort() {
        if (port != null) {
            return defaultPort ? port + " (默认)" : String.valueOf(port);
        }
        return portRaw == null ? "?" : portRaw;
    }
}
