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
package org.openhab.automation.pythonscripting.internal.console;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.module.ModuleDescriptor.Version;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedSet;
import java.util.UUID;

import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Language;
import org.openhab.automation.pythonscripting.internal.PythonScriptEngine;
import org.openhab.automation.pythonscripting.internal.PythonScriptEngineConfiguration;
import org.openhab.automation.pythonscripting.internal.PythonScriptEngineFactory;
import org.openhab.automation.pythonscripting.internal.fs.watch.PythonScriptFileWatcher;
import org.openhab.core.automation.module.script.ScriptEngineContainer;
import org.openhab.core.automation.module.script.ScriptEngineManager;
import org.openhab.core.config.core.ConfigDescription;
import org.openhab.core.config.core.ConfigDescriptionParameter;
import org.openhab.core.config.core.ConfigDescriptionRegistry;
import org.openhab.core.io.console.Console;
import org.openhab.core.io.console.ConsoleCommandCompleter;
import org.openhab.core.io.console.StringsCompleter;
import org.openhab.core.io.console.extensions.AbstractConsoleCommandExtension;
import org.openhab.core.io.console.extensions.ConsoleCommandExtension;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The {@link PythonConsoleCommandExtension} class
 *
 * @author Holger Hees - Initial contribution
 */
@NonNullByDefault
@Component(service = ConsoleCommandExtension.class)
public class PythonConsoleCommandExtension extends AbstractConsoleCommandExtension implements ConsoleCommandCompleter {
    private final Logger logger = LoggerFactory.getLogger(PythonConsoleCommandExtension.class);

    private static final String UPDATE_RELEASES_URL = "https://api.github.com/repos/openhab/openhab-python/releases";
    private static final String UPDATE_LATEST_URL = "https://api.github.com/repos/openhab/openhab-python/releases/latest";

    private static final String INFO = "info";
    private static final String CONSOLE = "console";
    private static final String PIP = "pip";
    private static final String PIP_INSTALL = "install";
    private static final String PIP_UNINSTALL = "uninstall";
    private static final String PIP_SHOW = "show";
    private static final String PIP_LIST = "list";
    private static final String UPDATE = "update";
    private static final String UPDATE_LIST = "list";
    private static final String UPDATE_CHECK = "check";
    private static final String UPDATE_INSTALL = "install";

    private static final List<String> COMMANDS = List.of(INFO, CONSOLE, UPDATE);
    private static final List<String> UPDATE_COMMANDS = List.of(UPDATE_LIST, UPDATE_CHECK, UPDATE_INSTALL);
    private static final List<String> PIP_COMMANDS = List.of(PIP_INSTALL, PIP_UNINSTALL, PIP_SHOW, PIP_LIST);

    private final ScriptEngineManager scriptEngineManager;
    private final PythonScriptEngineFactory pythonScriptEngineFactory;
    private final PythonScriptFileWatcher scriptFileWatcher;
    private final ConfigDescriptionRegistry configDescriptionRegistry;
    private final PythonScriptEngineConfiguration pythonScriptEngineConfiguration;

    private final String scriptType;

    @Activate
    public PythonConsoleCommandExtension( //
            @Reference ScriptEngineManager scriptEngineManager, //
            @Reference PythonScriptEngineFactory pythonScriptEngineFactory, //
            @Reference PythonScriptFileWatcher scriptFileWatcher, //
            @Reference ConfigDescriptionRegistry configDescriptionRegistry) {
        super("pythonscripting", "Python Scripting console utilities.");
        this.scriptEngineManager = scriptEngineManager;
        this.pythonScriptEngineFactory = pythonScriptEngineFactory;
        this.pythonScriptEngineConfiguration = pythonScriptEngineFactory.getConfiguration();
        this.scriptFileWatcher = scriptFileWatcher;
        this.scriptType = PythonScriptEngineFactory.SCRIPT_TYPE;
        this.configDescriptionRegistry = configDescriptionRegistry;
    }

    @Override
    public @Nullable ConsoleCommandCompleter getCompleter() {
        return this;
    }

    @Override
    public List<String> getUsages() {
        ArrayList<String> usages = new ArrayList<String>();
        usages.add(buildCommandUsage(INFO, "displays information about Python Scripting add-on"));
        usages.add(buildCommandUsage(CONSOLE, "starts an interactive python console"));
        usages.add(getUpdateUsage());
        if (pythonScriptEngineConfiguration.isVEnvEnabled()) {
            usages.add(getPipUsage());
        }
        return usages;
    }

    public String getUpdateUsage() {
        return buildCommandUsage(UPDATE + " <" + String.join("|", UPDATE_COMMANDS) + ">", "update helper lib module");
    }

    public String getPipUsage() {
        return buildCommandUsage(PIP + " <" + String.join("|", PIP_COMMANDS) + "> [optional pip specific arguments]",
                "manages python modules");
    }

    @Override
    public boolean complete(String[] args, int cursorArgumentIndex, int cursorPosition, List<String> candidates) {
        StringsCompleter completer = new StringsCompleter();
        SortedSet<String> strings = completer.getStrings();
        if (cursorArgumentIndex == 0) {
            strings.addAll(COMMANDS);
            if (pythonScriptEngineConfiguration.isVEnvEnabled()) {
                strings.add(PIP);
            }
        } else if (cursorArgumentIndex == 1) {
            if (PIP.equals(args[0])) {
                strings.addAll(PIP_COMMANDS);
            } else if (UPDATE.equals(args[0])) {
                strings.addAll(UPDATE_COMMANDS);
            }
        }

        return strings.isEmpty() ? false : completer.complete(args, cursorArgumentIndex, cursorPosition, candidates);
    }

    @Override
    public void execute(String[] args, Console console) {
        if (args.length > 0) {
            String command = args[0];
            switch (command) {
                case "--help":
                case "-h":
                    printUsage(console);
                    break;
                case INFO:
                    info(console);
                    break;
                case CONSOLE:
                    startConsole(console, Arrays.copyOfRange(args, 1, args.length));
                    break;
                case UPDATE:
                    executeUpdate(console, Arrays.copyOfRange(args, 1, args.length));
                    break;
                case PIP:
                    if (pythonScriptEngineConfiguration.isVEnvEnabled()) {
                        executePip(console, Arrays.copyOfRange(args, 1, args.length));
                        break;
                    }
                default:
                    console.println("Unknown command '" + command + "'");
                    printUsage(console);
                    break;
            }
        } else {
            printUsage(console);
        }
    }

    private void info(Console console) {
        console.println("Python Scripting Environment:");
        console.println("======================================");
        console.println("Runtime:");
        console.println("  Bundle version: " + pythonScriptEngineConfiguration.getBundleVersion());
        console.println("  GraalVM version: " + pythonScriptEngineConfiguration.getGraalVersion());
        Engine tempEngine = Engine.newBuilder().useSystemProperties(false).//
                out(OutputStream.nullOutputStream()).//
                err(OutputStream.nullOutputStream()).//
                option("engine.WarnInterpreterOnly", "false").//
                build();
        Language language = tempEngine.getLanguages().get("python");
        console.println("  Python version: " + language.getVersion());
        Version version = pythonScriptEngineConfiguration.getInstalledHelperLibVersion();
        console.println("  Helper lib version: " + (version != null ? version.toString() : "disabled"));
        console.println("  VEnv state: " + (pythonScriptEngineConfiguration.isVEnvEnabled() ? "enabled" : "disabled"));
        console.println("");
        console.println("Directories:");
        console.println("  Script path: " + scriptFileWatcher.getWatchPath());
        Path tempDirectory = pythonScriptEngineConfiguration.getTempDirectory();
        console.println("  Temp path: " + tempDirectory.toString());
        Path venvDirectory = pythonScriptEngineConfiguration.getVEnvDirectory();
        console.println("  VEnv path: " + venvDirectory.toString());

        console.println("");
        console.println("Python Scripting Add-on Configuration:");
        console.println("======================================");
        ConfigDescription configDescription = configDescriptionRegistry
                .getConfigDescription(URI.create(PythonScriptEngineFactory.CONFIG_DESCRIPTION_URI));

        if (configDescription == null) {
            console.println("No configuration found for Python Scripting add-on. This is probably a bug.");
            return;
        }

        List<ConfigDescriptionParameter> parameters = configDescription.getParameters();
        Map<String, String> config = pythonScriptEngineConfiguration.getConfigurations();
        configDescription.getParameters().forEach(parameter -> {
            if (parameter.getGroupName() == null) {
                console.println("  " + parameter.getName() + ": " + config.get(parameter.getName()));
            }
        });
        configDescription.getParameterGroups().forEach(group -> {
            String groupLabel = group.getLabel();
            if (groupLabel == null) {
                groupLabel = group.getName();
            }
            console.println("  " + groupLabel);
            parameters.forEach(parameter -> {
                if (!group.getName().equals(parameter.getGroupName())) {
                    return;
                }
                console.print("    " + parameter.getName() + ": ");
                String value = config.get(parameter.getName());
                if (value == null) {
                    console.println("not set");
                } else if (value.contains("\n")) {
                    console.println("    (multiline)");
                    console.println("      " + value.replace("\n", "\n    "));
                } else {
                    console.println(value);
                }
            });
            console.println("");
        });
    }

    private void startConsole(Console console, String[] args) {
        final String startInteractiveSessionCode = """
                import readline # optional, will allow Up/Down/History in the console
                import code

                vars = globals().copy()
                vars.update(locals())
                shell = code.InteractiveConsole(vars)
                try:
                    shell.interact()
                except SystemExit:
                    pass
                """;

        executePython(console, engine -> engine.eval(startInteractiveSessionCode), true);
    }

    private void executeUpdate(Console console, String[] args) {
        if (args.length == 0) {
            console.println("Missing update action");
            console.printUsage(getUpdateUsage());
        } else if (UPDATE_COMMANDS.indexOf(args[0]) == -1) {
            console.println("Unknown update action '" + args[0] + "'");
            console.printUsage(getUpdateUsage());
        } else {
            JsonElement rootElement = null;
            Version installedVersion = pythonScriptEngineConfiguration.getInstalledHelperLibVersion();
            Version providedVersion = pythonScriptEngineConfiguration.getProvidedHelperLibVersion();
            switch (args[0]) {
                case UPDATE_LIST:
                    rootElement = getReleaseData(UPDATE_RELEASES_URL, console);
                    if (rootElement != null) {
                        console.println("Version             Released            Active");
                        console.println("----------------------------------------------");
                        if (rootElement.isJsonArray()) {
                            JsonArray list = rootElement.getAsJsonArray();
                            for (JsonElement element : list.asList()) {
                                String tagName = element.getAsJsonObject().get("tag_name").getAsString();
                                String publishString = element.getAsJsonObject().get("published_at").getAsString();

                                boolean isInstalled = false;
                                try {
                                    Version availableVersion = PythonScriptEngineConfiguration
                                            .parseHelperLibVersion(tagName);
                                    if (availableVersion.equals(installedVersion)) {
                                        isInstalled = true;
                                    } else if (availableVersion.compareTo(providedVersion) < 0) {
                                        continue;
                                    }

                                    DateTimeFormatter df = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss",
                                            Locale.getDefault());
                                    OffsetDateTime publishDate = OffsetDateTime.parse(publishString);
                                    console.println(String.format("%-19s", tagName) + " "
                                            + String.format("%-19s", df.format(publishDate)) + " "
                                            + String.format("%-6s", (isInstalled ? "*" : "  ")));
                                } catch (IllegalArgumentException e) {
                                    // ignore not parseable version
                                }
                            }
                        } else {
                            console.println("Fetching releases failed. Invalid data");
                        }
                    }
                    break;
                case UPDATE_CHECK:
                    if (installedVersion == null) {
                        console.println("Helper libs disabled. Skipping update.");
                    } else {
                        rootElement = getReleaseData(UPDATE_LATEST_URL, console);
                        if (rootElement != null) {
                            JsonElement tagName = rootElement.getAsJsonObject().get("tag_name");
                            Version latestVersion = Version.parse(tagName.getAsString().substring(1));
                            if (latestVersion.compareTo(installedVersion) > 0) {
                                console.println("Update from version '" + installedVersion + "' to version '"
                                        + latestVersion.toString() + "' available.");
                            } else {
                                console.println("Latest version '" + installedVersion + "' already installed.");
                            }
                        }
                    }
                    break;
                case UPDATE_INSTALL:
                    if (args.length <= 1) {
                        console.println("Missing release name");
                        console.printUsage("pythonscripting update install <\"latest\"|version>");
                    } else {
                        String requestedVersionString = args[1];
                        JsonObject releaseObj = null;
                        Version releaseVersion = null;
                        if ("latest".equals(requestedVersionString)) {
                            rootElement = getReleaseData(UPDATE_LATEST_URL, console);
                            if (rootElement != null) {
                                releaseObj = rootElement.getAsJsonObject();//
                                JsonElement tagName = releaseObj.get("tag_name");
                                releaseVersion = PythonScriptEngineConfiguration
                                        .parseHelperLibVersion(tagName.getAsString());
                            }
                        } else {
                            try {
                                Version requestedVersion = PythonScriptEngineConfiguration
                                        .parseHelperLibVersion(requestedVersionString);
                                rootElement = getReleaseData(UPDATE_RELEASES_URL, console);
                                if (rootElement != null) {
                                    if (rootElement.isJsonArray()) {
                                        JsonArray list = rootElement.getAsJsonArray();
                                        for (JsonElement element : list.asList()) {
                                            JsonElement tagName = element.getAsJsonObject().get("tag_name");
                                            try {
                                                releaseVersion = PythonScriptEngineConfiguration
                                                        .parseHelperLibVersion(tagName.getAsString());
                                            } catch (IllegalArgumentException e) {
                                                continue;
                                            }
                                            if (releaseVersion.compareTo(requestedVersion) == 0) {
                                                releaseObj = element.getAsJsonObject();
                                                break;
                                            }
                                        }
                                    }
                                }
                            } catch (IllegalArgumentException e) {
                                // continue, if no version was found
                            }
                        }

                        if (releaseObj != null && releaseVersion != null) {
                            if (releaseVersion.equals(installedVersion)) {
                                console.println("Version '" + releaseVersion.toString() + "' already installed");
                                break;
                            } else if (releaseVersion.compareTo(providedVersion) < 0) {
                                console.println("Outdated version '" + releaseVersion.toString() + "' not supported");
                                break;
                            }

                            String zipballUrl = releaseObj.get("zipball_url").getAsString();

                            try {
                                pythonScriptEngineConfiguration.initHelperLib(zipballUrl, releaseVersion);
                                console.println("Version '" + releaseVersion.toString() + "' installed successfully");
                            } catch (URISyntaxException | IOException e) {
                                console.println("Fetching release zip '" + zipballUrl + "' file failed. ");
                                throw new IllegalArgumentException(e);
                            }
                        } else {
                            console.println("Version '" + requestedVersionString + "' not found. ");
                        }
                    }
                    break;
            }
        }
    }

    private @Nullable JsonElement getReleaseData(String url, Console console) {
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url))
                    .header("Accept", "application/vnd.github+json").GET().build();

            HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                JsonElement obj = JsonParser.parseString(response.body());
                return obj;
            } else {
                console.println("Fetching releases failed. Status code is " + response.statusCode());
            }

        } catch (IOException | InterruptedException e) {
            console.println("Fetching releases failed. Request interrupted " + e.getLocalizedMessage());
        }
        return null;
    }

    private void executePip(Console console, String[] args) {
        if (args.length == 0) {
            console.println("Missing pip action");
            console.printUsage(getPipUsage());
        } else if (PIP_COMMANDS.indexOf(args[0]) == -1) {
            console.println("Unknown pip action '" + args[1] + "'");
            console.printUsage(getPipUsage());
        } else {
            ArrayList<String> params = new ArrayList<String>(Arrays.asList(args));

            if (PIP_UNINSTALL.equals(args[0])) {
                try {
                    console.readLine("\nPress Enter to confirm uninstall or Ctrl+C to cancel.", null);
                    console.println("");
                    params.add(1, "-y");
                } catch (IOException e) {
                    console.println("Error: " + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    console.println("Operation cancelled.");
                    return;
                }
            }

            final String pipCode = """
                    import subprocess
                    import sys

                    command_list = [sys.executable, "-m", "pip"] + PARAMS
                    with subprocess.Popen(command_list, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
                        for line in proc.stdout:
                            print(line.rstrip())
                    """;

            executePython(console, engine -> {
                engine.getContext().setAttribute("PARAMS", params, ScriptContext.ENGINE_SCOPE);
                return engine.eval(pipCode);
            }, false);
        }
    }

    private void printLoadingMessage(Console console, boolean show) {
        String loadingMessage = "Loading Python script engine...";
        if (show) {
            console.print(loadingMessage);
        } else {
            // Clear the loading message
            console.print("\r" + " ".repeat(loadingMessage.length()) + "\r");
        }
    }

    /*
     * Create Python engine.
     *
     * withFullContext = true => means a full openHAB-managed script engine with scoped variables
     * including any injected required modules.
     */
    private @Nullable Object executePython(@Nullable Console console, EngineEvalFunction process,
            boolean withFullContext) {
        String scriptIdentifier = null;
        try {
            ScriptEngine engine = null;
            if (console != null) {
                printLoadingMessage(console, true);
            }

            if (withFullContext) {
                scriptIdentifier = "python-console-" + UUID.randomUUID().toString();
                ScriptEngineContainer container = scriptEngineManager.createScriptEngine(scriptType, scriptIdentifier);
                if (container == null) {
                    if (console != null) {
                        console.println("Error: Unable to create Python script engine.");
                    }
                    return null;
                }
                engine = container.getScriptEngine();
            } else {
                engine = pythonScriptEngineFactory.createScriptEngine(scriptType);
            }

            if (engine == null) {
                throw new ScriptException("Unable to create Python script engine.");
            }

            if (console != null) {
                printLoadingMessage(console, false);
            }

            if (console != null) {
                engine.getContext().setAttribute(PythonScriptEngine.CONTEXT_KEY_ENGINE_LOGGER_INPUT,
                        createInputStream(console), ScriptContext.ENGINE_SCOPE);
            }

            engine.getContext().setAttribute(PythonScriptEngine.CONTEXT_KEY_ENGINE_LOGGER_OUTPUT, System.out,
                    ScriptContext.ENGINE_SCOPE);
            return process.apply(engine);
        } catch (ScriptException e) {
            if (console != null) {
                console.println("Error: " + e.getMessage());
            } else {
                logger.warn("Error: {}", e.getMessage());
            }
            return null;
        } finally {
            if (scriptIdentifier != null) {
                scriptEngineManager.removeEngine(scriptIdentifier);
            }
        }
    }

    public InputStream createInputStream(Console console) {
        return new InputStream() {
            byte @Nullable [] buffer = null;
            int pos = 0;

            @Override
            public int read() throws IOException {
                if (pos < 0) {
                    pos = 0;
                    return -1;
                } else if (buffer == null) {
                    assert pos == 0;
                    try {
                        String line = console.readLine("", null);
                        buffer = line.getBytes(StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        return -1;
                    }
                }
                if (pos == buffer.length) {
                    buffer = null;
                    pos = -1;
                    return '\n';
                } else {
                    return buffer[pos++];
                }
            }
        };
    }

    @FunctionalInterface
    public interface EngineEvalFunction {
        @Nullable
        Object apply(ScriptEngine e) throws ScriptException;
    }
}
