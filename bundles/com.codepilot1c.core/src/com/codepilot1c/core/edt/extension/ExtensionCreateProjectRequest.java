package com.codepilot1c.core.edt.extension;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import com._1c.g5.v8.dt.metadata.mdclass.CompatibilityMode;
import com._1c.g5.v8.dt.metadata.mdclass.ConfigurationExtensionPurpose;
import com._1c.g5.v8.dt.platform.version.Version;
import com.codepilot1c.core.edt.metadata.MetadataOperationCode;
import com.codepilot1c.core.edt.metadata.MetadataOperationException;

/**
 * Request for creating a new EDT extension project.
 */
public record ExtensionCreateProjectRequest(
        String baseProjectName,
        String extensionProjectName,
        String projectPath,
        String version,
        String configurationName,
        String purpose,
        String compatibilityMode
) {
    public void validate() {
        if (baseProjectName == null || baseProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.PROJECT_NOT_FOUND,
                    "base_project is required", false); //$NON-NLS-1$
        }
        if (extensionProjectName == null || extensionProjectName.isBlank()) {
            throw new MetadataOperationException(
                    MetadataOperationCode.EXTENSION_PROJECT_NOT_FOUND,
                    "extension_project is required", false); //$NON-NLS-1$
        }
        if (normalizedBaseProjectName().equalsIgnoreCase(normalizedExtensionProjectName())) {
            throw new MetadataOperationException(
                    MetadataOperationCode.KNOWLEDGE_REQUIRED,
                    "extension_project must differ from base_project", false); //$NON-NLS-1$
        }
        if (projectPath != null && !projectPath.isBlank()) {
            try {
                Path.of(projectPath.trim());
            } catch (InvalidPathException e) {
                throw new MetadataOperationException(
                        MetadataOperationCode.INVALID_PROPERTY_VALUE,
                        "Invalid project_path: " + e.getMessage(), false); //$NON-NLS-1$
            }
        }
        if (version != null && !version.isBlank()) {
            parseSupportedPlatformVersion(version);
        }
        effectivePurpose();
        effectiveCompatibilityMode();
    }

    /**
     * Parses the {@code version} parameter as a 1C:Enterprise <em>platform runtime</em> version and
     * rejects anything the installed EDT cannot run.
     *
     * <p>The platform writes this value into {@code DT-INF/PROJECT.PMF} as {@code Runtime-Version}
     * and then starts the new project context against it. A syntactically valid but unsupported
     * value (the classic case: the extension's own product version, e.g. {@code 1.0.0}) is accepted
     * by {@link Version#create(String)} and only blows up much later, inside the platform's
     * {@code INITIALIZATION} lifecycle phase, where the rollback handler masks the real cause. So the
     * value has to be rejected here, before {@code IExtensionProjectManager.create} is ever called.</p>
     *
     * @param raw the raw {@code version} parameter, never {@code null} or blank
     * @return the parsed supported platform version
     * @throws MetadataOperationException when the value is not a supported platform runtime version
     */
    public static Version parseSupportedPlatformVersion(String raw) {
        String trimmed = raw == null ? "" : raw.trim(); //$NON-NLS-1$
        Version parsed = null;
        try {
            parsed = Version.create(trimmed);
        } catch (RuntimeException e) {
            parsed = null;
        }
        List<Version> supported = Version.getPlatformSupportVersions();
        if (parsed == null || !supported.contains(parsed)) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Unsupported platform version: " + trimmed //$NON-NLS-1$
                            + ". 'version' is the 1C:Enterprise platform runtime version of the new" //$NON-NLS-1$
                            + " extension project (for example 8.3.27), not the extension's own" //$NON-NLS-1$
                            + " product version. Omit it to inherit the base project's runtime" //$NON-NLS-1$
                            + " version. Supported: " //$NON-NLS-1$
                            + supported.stream().map(Version::toString).collect(Collectors.joining(", ")), //$NON-NLS-1$
                    false);
        }
        return parsed;
    }

    public String normalizedBaseProjectName() {
        return baseProjectName == null ? null : baseProjectName.trim();
    }

    public String normalizedExtensionProjectName() {
        return extensionProjectName == null ? null : extensionProjectName.trim();
    }

    public String effectiveConfigurationName() {
        if (configurationName == null || configurationName.isBlank()) {
            return normalizedExtensionProjectName();
        }
        return configurationName.trim();
    }

    public Path effectiveProjectPath(Path workspaceRoot) {
        if (projectPath == null || projectPath.isBlank()) {
            return workspaceRoot.resolve(normalizedExtensionProjectName());
        }
        return Path.of(projectPath.trim());
    }

    public Version effectiveVersion(Version fallback) {
        if (version == null || version.isBlank()) {
            return fallback != null ? fallback : Version.LATEST;
        }
        return parseSupportedPlatformVersion(version);
    }

    public ConfigurationExtensionPurpose effectivePurpose() {
        if (purpose == null || purpose.isBlank()) {
            return ConfigurationExtensionPurpose.ADD_ON;
        }
        String normalized = purpose.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        if ("ADDON".equals(normalized)) { //$NON-NLS-1$
            normalized = "ADD_ON"; //$NON-NLS-1$
        }
        try {
            return ConfigurationExtensionPurpose.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Unsupported purpose: " + purpose, false); //$NON-NLS-1$
        }
    }

    public CompatibilityMode effectiveCompatibilityMode() {
        if (compatibilityMode == null || compatibilityMode.isBlank()) {
            return null;
        }
        String normalized = normalizeCompatibilityModeToken(compatibilityMode);
        try {
            return CompatibilityMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new MetadataOperationException(
                    MetadataOperationCode.INVALID_PROPERTY_VALUE,
                    "Unsupported compatibility_mode: " + compatibilityMode, false); //$NON-NLS-1$
        }
    }

    private String normalizeCompatibilityModeToken(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("VERSION")) { //$NON-NLS-1$
            normalized = normalized.substring("VERSION".length()); //$NON-NLS-1$
        }
        if (normalized.matches("^\\d+\\.\\d+\\.\\d+$")) { //$NON-NLS-1$
            String[] parts = normalized.split("\\."); //$NON-NLS-1$
            return "VERSION" + parts[0] + "_" + parts[1] + parts[2]; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (normalized.matches("^\\d+_\\d+_\\d+$")) { //$NON-NLS-1$
            String[] parts = normalized.split("_"); //$NON-NLS-1$
            return "VERSION" + parts[0] + "_" + parts[1] + parts[2]; //$NON-NLS-1$ //$NON-NLS-2$
        }
        if (normalized.matches("^\\d+_\\d+$")) { //$NON-NLS-1$
            return "VERSION" + normalized; //$NON-NLS-1$
        }
        return normalized.startsWith("VERSION") ? normalized : "VERSION" + normalized; //$NON-NLS-1$ //$NON-NLS-2$
    }
}
