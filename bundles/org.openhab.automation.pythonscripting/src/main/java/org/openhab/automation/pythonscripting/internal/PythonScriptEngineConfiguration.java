/*
 * Copyright (c) 2010-2025 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.automation.pythonscripting.internal;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.module.ModuleDescriptor.Version;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.OpenHAB;
import org.openhab.core.automation.module.script.ScriptEngineFactory;
import org.openhab.core.config.core.Configuration;
import org.osgi.framework.FrameworkUtil;
import org.osgi.service.component.annotations.Activate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Processes Python Configuration Parameters.
 *
 * @author Holger Hees - Initial contribution
 */
@NonNullByDefault
public class PythonScriptEngineConfiguration {

    private final Logger logger = LoggerFactory.getLogger(PythonScriptEngineConfiguration.class);

    private static final String SYSTEM_PROPERTY_POLYGLOT_ENGINE_USERRESOURCECACHE = "polyglot.engine.userResourceCache";

    private static final String SYSTEM_PROPERTY_JAVA_IO_TMPDIR = "java.io.tmpdir";

    private static final String RESOURCE_SEPARATOR = "/";

    public static final Path PYTHON_DEFAULT_PATH = Paths.get(OpenHAB.getConfigFolder(), "automation", "python");
    public static final Path PYTHON_LIB_PATH = PYTHON_DEFAULT_PATH.resolve("lib");

    private static final Path PYTHON_OPENHAB_LIB_PATH = PYTHON_LIB_PATH.resolve("openhab");

    public static final Path PYTHON_WRAPPER_FILE_PATH = PYTHON_OPENHAB_LIB_PATH.resolve("__wrapper__.py");
    private static final Path PYTHON_INIT_FILE_PATH = PYTHON_OPENHAB_LIB_PATH.resolve("__init__.py");

    public static final int INJECTION_DISABLED = 0;
    public static final int INJECTION_ENABLED_FOR_ALL_SCRIPTS = 1;
    public static final int INJECTION_ENABLED_FOR_NON_FILE_BASED_SCRIPTS = 2;

    // The variable names must match the configuration keys in config.xml
    public static class PythonScriptingConfiguration {
        public boolean scopeEnabled = true;
        public boolean helperEnabled = true;
        public int injectionEnabled = INJECTION_ENABLED_FOR_NON_FILE_BASED_SCRIPTS;
        public boolean dependencyTrackingEnabled = true;
        public boolean cachingEnabled = true;
        public boolean jythonEmulation = false;
        public boolean nativeModules = false;
        public String pipModules = "";
    }

    private PythonScriptingConfiguration configuration = new PythonScriptingConfiguration();
    private Path bytecodeDirectory;
    private Path tempDirectory;
    private Path venvDirectory;
    private @Nullable Path venvExecutable = null;

    private @Nullable Version helperLibVersion = null;

    @Activate
    public PythonScriptEngineConfiguration() {
        Path userdataDir = Paths.get(OpenHAB.getUserDataFolder());

        String tmpDir = System.getProperty(SYSTEM_PROPERTY_JAVA_IO_TMPDIR);
        if (tmpDir != null) {
            tempDirectory = Paths.get(tmpDir);
        } else {
            tempDirectory = userdataDir.resolve("tmp");
        }

        String packageName = PythonScriptEngineConfiguration.class.getPackageName();
        packageName = packageName.substring(0, packageName.lastIndexOf("."));
        Path bindingDirectory = userdataDir.resolve("cache").resolve(packageName);

        Properties props = System.getProperties();
        props.setProperty(SYSTEM_PROPERTY_POLYGLOT_ENGINE_USERRESOURCECACHE, bindingDirectory.toString());
        bytecodeDirectory = initDirectory(bindingDirectory, "resources");
        venvDirectory = initDirectory(bindingDirectory, "venv");

        Path venvPythonBin = venvDirectory.resolve("bin").resolve("graalpy");
        if (Files.exists(venvPythonBin)) {
            venvExecutable = venvPythonBin;
        }
    }

    /**
     * Update configuration
     *
     * @param config Configuration parameters to apply to ScriptEngine
     */
    void update(Map<String, Object> config, ScriptEngineFactory factory) {
        logger.trace("Python Script Engine Configuration: {}", config);

        configuration = new Configuration(config).as(PythonScriptingConfiguration.class);

        if (isHelperEnabled()) {
            initHelperLib();
        }

        if (isVEnvEnabled()) {
            initPipModules(factory);
        }
    }

    public boolean isScopeEnabled() {
        return configuration.scopeEnabled;
    }

    public boolean isHelperEnabled() {
        return configuration.helperEnabled;
    }

    public boolean isInjection(int type) {
        return configuration.injectionEnabled == type;
    }

    public boolean isDependencyTrackingEnabled() {
        return configuration.dependencyTrackingEnabled;
    }

    public boolean isCachingEnabled() {
        return configuration.cachingEnabled;
    }

    public boolean isJythonEmulation() {
        return configuration.jythonEmulation;
    }

    public boolean isNativeModulesEnabled() {
        return configuration.nativeModules;
    }

    public String getPIPModules() {
        return configuration.pipModules;
    }

    public Path getBytecodeDirectory() {
        return bytecodeDirectory;
    }

    public Path getTempDirectory() {
        return tempDirectory;
    }

    public Path getVEnvDirectory() {
        return venvDirectory;
    }

    public @Nullable Path getVEnvExecutable() {
        return venvExecutable;
    }

    public boolean isVEnvEnabled() {
        return venvExecutable != null;
    }

    public @Nullable Version getHelperLibVersion() {
        return helperLibVersion;
    }

    /**
     * Returns the current configuration as a map.
     * This is used to display the configuration in the console.
     */
    public Map<String, String> getConfigurations() {
        ObjectMapper objectMapper = new ObjectMapper();
        Map<String, Object> objectMap = objectMapper.convertValue(configuration,
                new TypeReference<Map<String, Object>>() {
                });
        return objectMap.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, entry -> {
            if (entry.getValue() instanceof List<?> listValue) {
                return listValue.stream().map(Object::toString).collect(Collectors.joining("\n"));
            }
            return entry.getValue().toString();
        }));
    }

    private Path initDirectory(Path base, String name) {
        Path directory = base.resolve(name);
        if (!Files.exists(directory)) {
            directory.toFile().mkdirs();
        }
        return directory;
    }

    private void initPipModules(ScriptEngineFactory factory) {
        String pipModulesConfig = configuration.pipModules.strip();

        if (pipModulesConfig.isEmpty()) {
            return;
        }

        List<String> pipModules = Arrays.stream(pipModulesConfig.split(",")).map(String::trim)
                .filter(module -> !module.isEmpty()).collect(Collectors.toList());

        if (pipModules.isEmpty()) {
            return;
        }

        final String PIP = """
                import subprocess
                import sys

                command_list = [sys.executable, "-m", "pip", "install"] + pipModules
                proc = subprocess.run(command_list, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
                if proc.returncode != 0:
                    print(proc.stdout)
                    exit(1)
                """;

        ScriptEngine engine = factory.createScriptEngine(PythonScriptEngineFactory.SCRIPT_TYPE);
        engine.getContext().setAttribute("pipModules", pipModules, ScriptContext.ENGINE_SCOPE);
        try {
            logger.info("Checking for pip module{} '{}'", pipModules.size() > 1 ? "s" : "", configuration.pipModules);
            engine.eval(PIP);
        } catch (ScriptException e) {
            logger.warn("Error installing pip module{}", pipModules.size() > 1 ? "s" : "");
            logger.trace("TRACE:", unwrap(e));
        }
    }

    private void initHelperLib() {
        logger.info("Checking for helper libs");

        try {
            String pathSeparator = FileSystems.getDefault().getSeparator();
            String resourceLibPath = PYTHON_OPENHAB_LIB_PATH.toString()
                    .substring(PYTHON_DEFAULT_PATH.toString().length()) + pathSeparator;
            if (!RESOURCE_SEPARATOR.equals(pathSeparator)) {
                resourceLibPath = resourceLibPath.replace(pathSeparator, RESOURCE_SEPARATOR);
            }

            if (Files.exists(PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH)) {
                try (Stream<Path> files = Files.list(PYTHON_OPENHAB_LIB_PATH)) {
                    if (files.count() > 0) {
                        Pattern pattern = Pattern.compile("__version__\\s*=\\s*\"([0-9]+\\.[0-9]+\\.[0-9]+)\"",
                                Pattern.CASE_INSENSITIVE);
                        Version includedVersion = null;
                        try (InputStream is = PythonScriptEngineConfiguration.class.getClassLoader()
                                .getResourceAsStream(
                                        resourceLibPath + PYTHON_INIT_FILE_PATH.getFileName().toString())) {
                            try (InputStreamReader isr = new InputStreamReader(is);
                                    BufferedReader reader = new BufferedReader(isr)) {
                                String fileContent = reader.lines().collect(Collectors.joining(System.lineSeparator()));
                                Matcher includedMatcher = pattern.matcher(fileContent);
                                if (includedMatcher.find()) {
                                    includedVersion = Version.parse(includedMatcher.group(1));
                                }
                            }
                        }

                        Version currentVersion = null;
                        String fileContent = Files.readString(PYTHON_INIT_FILE_PATH, StandardCharsets.UTF_8);
                        Matcher currentMatcher = pattern.matcher(fileContent);
                        if (currentMatcher.find()) {
                            currentVersion = Version.parse(currentMatcher.group(1));
                        }

                        helperLibVersion = currentVersion;

                        if (currentVersion == null) {
                            logger.warn("Unable to detect installed helper lib version. Skip installing helper libs.");
                            return;
                        } else if (includedVersion == null) {
                            logger.error("Unable to detect provided helper lib version. Skip installing helper libs.");
                            return;
                        } else if (currentVersion.compareTo(includedVersion) >= 0) {
                            // logger.info("Newest helper lib version is deployed.");
                            return;
                        }
                    }
                }
            }

            logger.info("Deploy helper libs into {}.", PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH);

            if (Files.exists(PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH)) {
                try (Stream<Path> paths = Files.walk(PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH)) {
                    paths.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
                }
            }

            initDirectory(PythonScriptEngineConfiguration.PYTHON_DEFAULT_PATH);
            initDirectory(PythonScriptEngineConfiguration.PYTHON_LIB_PATH);
            initDirectory(PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH);

            Enumeration<URL> resourceFiles = FrameworkUtil.getBundle(PythonScriptEngineConfiguration.class)
                    .findEntries(resourceLibPath, "*.py", true);

            while (resourceFiles.hasMoreElements()) {
                URL resourceFile = resourceFiles.nextElement();
                String resourcePath = resourceFile.getPath();

                try (InputStream is = PythonScriptEngineConfiguration.class.getClassLoader()
                        .getResourceAsStream(resourcePath)) {
                    Path target = PythonScriptEngineConfiguration.PYTHON_OPENHAB_LIB_PATH
                            .resolve(resourcePath.substring(resourcePath.lastIndexOf(RESOURCE_SEPARATOR) + 1));

                    Files.copy(is, target);
                    File file = target.toFile();
                    file.setReadable(true, false);
                    file.setWritable(true, true);
                }
            }
        } catch (Exception e) {
            logger.error("Exception during helper lib initialisation", e);
        }
    }

    private void initDirectory(Path path) {
        File directory = path.toFile();
        if (!directory.exists()) {
            directory.mkdir();
            directory.setExecutable(true, false);
            directory.setReadable(true, false);
            directory.setWritable(true, true);
        }
    }

    /**
     * Unwraps the cause of an exception, if it has one.
     *
     * Since a user cares about the _Ruby_ stack trace of the throwable, not
     * the details of where openHAB called it.
     */
    private static Throwable unwrap(Throwable e) {
        Throwable cause = e.getCause();
        if (cause != null) {
            return cause;
        }
        return e;
    }
}
