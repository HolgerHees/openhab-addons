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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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

/**
 * The {@link PythonConsoleCommandExtension} class
 *
 * @author Holger Hees - Initial contribution
 */
@NonNullByDefault
@Component(service = ConsoleCommandExtension.class)
public class PythonConsoleCommandExtension extends AbstractConsoleCommandExtension implements ConsoleCommandCompleter {
    private final Logger logger = LoggerFactory.getLogger(PythonConsoleCommandExtension.class);

    private static final String INFO = "info";
    private static final String CONSOLE = "console";
    private static final String PIP = "pip";

    private static final List<String> SUB_COMMANDS = List.of(INFO, CONSOLE);

    private static final List<String> PIP_SUB_COMMANDS = List.of("install", "uninstall", "show", "list");

    private final ScriptEngineManager scriptEngineManager;
    private final PythonScriptEngineFactory pythonScriptEngineFactory;
    private final PythonScriptFileWatcher scriptFileWatcher;
    private final ConfigDescriptionRegistry configDescriptionRegistry;

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
        if (pythonScriptEngineFactory.getConfiguration().isVEnvEnabled()) {
            usages.add(getPipUsage());
        }
        return usages;
    }

    public String getPipUsage() {
        return buildCommandUsage(PIP + " " + String.join("|", PIP_SUB_COMMANDS) + " <optional arguments>",
                "manages python modules");
    }

    @Override
    public boolean complete(String[] args, int cursorArgumentIndex, int cursorPosition, List<String> candidates) {
        StringsCompleter completer = new StringsCompleter();
        SortedSet<String> strings = completer.getStrings();
        if (cursorArgumentIndex == 0) {
            strings.addAll(SUB_COMMANDS);
            if (pythonScriptEngineFactory.getConfiguration().isVEnvEnabled()) {
                strings.add(PIP);
            }
        } else if (cursorArgumentIndex == 1) {
            if (PIP.equals(args[0])) {
                strings.addAll(PIP_SUB_COMMANDS);
            }
        }

        if (!strings.isEmpty()) {
            return completer.complete(args, cursorArgumentIndex, cursorPosition, candidates);
        }

        return false;
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
                case PIP:
                    if (pythonScriptEngineFactory.getConfiguration().isVEnvEnabled() && args.length > 1) {
                        if (PIP_SUB_COMMANDS.indexOf(args[1]) != -1) {
                            pip(console, Arrays.copyOfRange(args, 1, args.length));
                        } else {
                            console.println("Unknown sub command '" + args[1] + "'");
                            console.printUsage(getPipUsage());
                        }
                    } else {
                        console.println("Missing sub_command");
                        console.printUsage(getPipUsage());
                    }
                    break;
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
        console.println("Runtime:");

        Properties p = new Properties();
        try {
            InputStream is = PythonScriptEngineConfiguration.class.getResourceAsStream("/build.properties");
            if (is != null) {
                p.load(is);
                console.println("  GraalVM version: " + p.getProperty("graalpy.version"));
            }
        } catch (IOException e) {
        }

        Engine tempEngine = Engine.newBuilder().useSystemProperties(false).//
                out(OutputStream.nullOutputStream()).//
                err(OutputStream.nullOutputStream()).//
                option("engine.WarnInterpreterOnly", "false").//
                build();
        Language language = tempEngine.getLanguages().get("python");
        console.println("  Python version: " + language.getVersion());
        Version version = pythonScriptEngineFactory.getConfiguration().getHelperLibVersion();
        console.println("  Helper lib version: " + (version != null ? version.toString() : "disabled"));
        console.println("  VEnv state: "
                + (pythonScriptEngineFactory.getConfiguration().isVEnvEnabled() ? "enabled" : "disabled"));

        console.println("");
        console.println("Directories:");
        console.println("  Script path: " + scriptFileWatcher.getWatchPath());
        Path tempDirectory = pythonScriptEngineFactory.getConfiguration().getTempDirectory();
        console.println("  Temp path: " + tempDirectory.toString());
        Path venvDirectory = pythonScriptEngineFactory.getConfiguration().getVEnvDirectory();
        console.println("  VEnv path: " + venvDirectory.toString());

        console.println("");
        console.println("Add-on configuration:");
        ConfigDescription configDescription = configDescriptionRegistry
                .getConfigDescription(URI.create(PythonScriptEngineFactory.CONFIG_DESCRIPTION_URI));

        if (configDescription == null) {
            console.println("No configuration found for Python Scripting add-on. This is probably a bug.");
            return;
        }

        List<ConfigDescriptionParameter> parameters = configDescription.getParameters();
        Map<String, String> config = pythonScriptEngineFactory.getConfiguration().getConfigurations();
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
        final String START_INTERACTIVE_SESSION = """
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

        executePython(console, engine -> engine.eval(START_INTERACTIVE_SESSION), true);
    }

    private void pip(Console console, String[] args) {
        final String PIP = """
                import subprocess
                import sys

                cmd = ARGV.copy()
                if cmd[0] == "uninstall":
                    cmd.insert(1,"-y")

                command_list = [sys.executable, "-m", "pip"] + cmd
                with subprocess.Popen(command_list, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True) as proc:
                    for line in proc.stdout:
                        print(line.rstrip())
                """;

        executePython(console, engine -> {
            engine.getContext().setAttribute("ARGV", args, ScriptContext.ENGINE_SCOPE);
            return engine.eval(PIP);
        }, false);
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
