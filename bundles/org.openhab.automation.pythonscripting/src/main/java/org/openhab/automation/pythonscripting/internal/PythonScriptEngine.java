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

import static org.openhab.core.automation.module.script.ScriptEngineFactory.*;
import static org.openhab.core.automation.module.script.ScriptTransformationService.OPENHAB_TRANSFORMATION_SCRIPT;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AccessMode;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.script.ScriptContext;
import javax.script.ScriptException;

import org.eclipse.jdt.annotation.Nullable;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.io.IOAccess;
import org.openhab.automation.pythonscripting.internal.fs.DelegatingFileSystem;
import org.openhab.automation.pythonscripting.internal.fs.watch.PythonDependencyTracker;
import org.openhab.automation.pythonscripting.internal.graal.GraalPythonScriptEngine;
import org.openhab.automation.pythonscripting.internal.scriptengine.InvocationInterceptingScriptEngineWithInvocableAndCompilableAndAutoCloseable;
import org.openhab.automation.pythonscripting.internal.scriptengine.helper.ContextInput;
import org.openhab.automation.pythonscripting.internal.scriptengine.helper.ContextOutput;
import org.openhab.automation.pythonscripting.internal.scriptengine.helper.ContextOutputLogger;
import org.openhab.automation.pythonscripting.internal.scriptengine.helper.LifecycleTracker;
import org.openhab.automation.pythonscripting.internal.wrapper.ScriptExtensionModuleProvider;
import org.openhab.core.automation.module.script.ScriptExtensionAccessor;
import org.openhab.core.items.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

/**
 * GraalPython ScriptEngine implementation
 *
 * @author Holger Hees - Initial contribution
 * @author Jeff James - Initial contribution
 */
public class PythonScriptEngine
        extends InvocationInterceptingScriptEngineWithInvocableAndCompilableAndAutoCloseable<GraalPythonScriptEngine>
        implements Lock {
    private final Logger logger = LoggerFactory.getLogger(PythonScriptEngine.class);

    public static final String CONTEXT_KEY_ENGINE_LOGGER_OUTPUT = "ctx.engine-logger-output";
    public static final String CONTEXT_KEY_ENGINE_LOGGER_INPUT = "ctx.engine-logger-input";
    public static final String CONTEXT_KEY_SCRIPT_FILENAME = "javax.script.filename";

    private static final String SYSTEM_PROPERTY_ATTACH_LIBRARY_FAILURE_ACTION = "polyglotimpl.AttachLibraryFailureAction";

    private static final String PYTHON_OPTION_PYTHONPATH = "python.PythonPath";
    private static final String PYTHON_OPTION_EMULATEJYTHON = "python.EmulateJython";
    private static final String PYTHON_OPTION_POSIXMODULEBACKEND = "python.PosixModuleBackend";
    private static final String PYTHON_OPTION_DONTWRITEBYTECODEFLAG = "python.DontWriteBytecodeFlag";
    private static final String PYTHON_OPTION_FORCEIMPORTSITE = "python.ForceImportSite";
    private static final String PYTHON_OPTION_CHECKHASHPYCSMODE = "python.CheckHashPycsMode";
    private static final String PYTHON_OPTION_ALWAYSRUNEXCEPTHOOK = "python.AlwaysRunExcepthook";

    private static final String PYTHON_OPTION_EXECUTABLE = "python.Executable";
    // private static final String PYTHON_OPTION_PYTHONHOME = "python.PythonHome";
    // private static final String PYTHON_OPTION_SYSPREFIX = "python.SysPrefix";
    private static final String PYTHON_OPTION_NATIVEMODULES = "python.NativeModules";
    private static final String PYTHON_OPTION_ISOLATENATIVEMODULES = "python.IsolateNativeModules";

    private static final String PYTHON_OPTION_CACHEDIR = "python.PyCachePrefix";

    private static final int STACK_TRACE_LENGTH = 5;

    public static final String LOGGER_INIT_NAME = "__logger_init__";

    /** Shared Polyglot {@link Engine} across all instances of {@link PythonScriptEngine} */
    private static final Engine ENGINE = Engine.newBuilder().allowExperimentalOptions(true)
            .option("engine.WarnInterpreterOnly", "false").build();

    /** Provides unlimited host access as well as custom translations from Python to Java Objects */
    private static final HostAccess HOST_ACCESS = HostAccess.newBuilder(HostAccess.ALL)
            // Translate python datetime with timezone to java.time.ZonedDateTime
            .targetTypeMapping(Value.class, ZonedDateTime.class,
                    v -> v.hasMember("ctime") && v.hasMember("isoformat") && v.hasMember("tzinfo")
                            && !v.getMember("tzinfo").isNull(),
                    v -> ZonedDateTime.parse(v.invokeMember("isoformat").asString()),
                    HostAccess.TargetMappingPrecedence.LOW)

            // Translate python datetime without timezone to java.time.Instant
            .targetTypeMapping(Value.class, Instant.class,
                    v -> v.hasMember("ctime") && v.hasMember("isoformat") && v.hasMember("tzinfo")
                            && v.getMember("tzinfo").isNull(),
                    v -> Instant.parse(v.invokeMember("isoformat").asString() + "Z"),
                    HostAccess.TargetMappingPrecedence.LOW)

            // Translate python timedelta to java.time.Duration
            .targetTypeMapping(Value.class, Duration.class,
                    // picking two members to check as Duration has many common function names
                    v -> v.hasMember("total_seconds") && v.hasMember("total_seconds"),
                    v -> Duration.ofNanos(Math.round(v.invokeMember("total_seconds").asDouble() * 1000000000)),
                    HostAccess.TargetMappingPrecedence.LOW)

            // Translate python item to org.openhab.core.items.Item
            .targetTypeMapping(Value.class, Item.class, v -> v.hasMember("raw_item"),
                    v -> v.getMember("raw_item").as(Item.class), HostAccess.TargetMappingPrecedence.LOW)

            // Translate python array to java.util.Set
            .targetTypeMapping(Value.class, Set.class, v -> v.hasArrayElements(),
                    PythonScriptEngine::transformArrayToSet, HostAccess.TargetMappingPrecedence.LOW)

            .build();

    /** {@link Lock} synchronization of multi-thread access */
    private final Lock lock = new ReentrantLock();

    // these fields start as null because they are populated on first use
    private @Nullable Consumer<String> scriptDependencyListener;
    private final ScriptExtensionModuleProvider scriptExtensionModuleProvider;
    private final LifecycleTracker lifecycleTracker;

    private PythonScriptEngineConfiguration pythonScriptEngineConfiguration;

    private boolean initialized = false;
    private boolean closed = false;

    private final ContextOutput scriptOutputStream;
    private final ContextOutput scriptErrorStream;
    private final ContextInput scriptInputStream;

    /**
     * Creates an implementation of ScriptEngine {@code (& Invocable)}, wrapping the contained engine,
     * that tracks the script lifecycle and provides hooks for scripts to do so too.
     */
    public PythonScriptEngine(PythonDependencyTracker pythonDependencyTracker,
            PythonScriptEngineConfiguration pythonScriptEngineConfiguration) {
        this.pythonScriptEngineConfiguration = pythonScriptEngineConfiguration;

        scriptOutputStream = new ContextOutput(new ContextOutputLogger(logger, Level.INFO));
        scriptErrorStream = new ContextOutput(new ContextOutputLogger(logger, Level.ERROR));
        scriptInputStream = new ContextInput(System.in);

        lifecycleTracker = new LifecycleTracker();
        scriptExtensionModuleProvider = new ScriptExtensionModuleProvider();

        // disable warning about missing TruffleAttach library (is only available in graalvm)
        Properties props = System.getProperties();
        props.setProperty(SYSTEM_PROPERTY_ATTACH_LIBRARY_FAILURE_ACTION, "ignore");

        Context.Builder contextConfig = Context.newBuilder(GraalPythonScriptEngine.LANGUAGE_ID) //
                .out(scriptOutputStream) //
                .err(scriptErrorStream) //
                .in(scriptInputStream) //
                .allowIO(IOAccess.newBuilder() //
                        .allowHostSocketAccess(true) //
                        .fileSystem(new DelegatingFileSystem(FileSystems.getDefault().provider()) {
                            @Override
                            public void checkAccess(Path path, Set<? extends AccessMode> modes,
                                    LinkOption... linkOptions) throws IOException {
                                if (pythonScriptEngineConfiguration.isDependencyTrackingEnabled()) {
                                    if (path.startsWith(PythonScriptEngineConfiguration.PYTHON_LIB_PATH)) {
                                        Consumer<String> localScriptDependencyListener = scriptDependencyListener;
                                        if (localScriptDependencyListener != null) {
                                            localScriptDependencyListener.accept(path.toString());
                                        }
                                    }
                                }
                                super.checkAccess(path, modes, linkOptions);
                            }

                            @Override
                            public Path getTempDirectory() {
                                return pythonScriptEngineConfiguration.getTempDirectory();
                            }

                            @Override
                            public void setAttribute(Path path, String attribute, Object value, LinkOption... options)
                                    throws IOException {
                                Files.setAttribute(path, attribute, value, options);
                            }
                        }).build()) //
                .allowHostAccess(HOST_ACCESS) //
                // usage of .allowAllAccess(true) includes
                // - allowCreateThread(true)
                // - allowCreateProcess(true)
                // - allowHostClassLoading(true)
                // - allowHostClassLookup(true)
                // - allowPolyglotAccess(PolyglotAccess.ALL)
                // - allowIO(true)
                // - allowEnvironmentAccess(EnvironmentAccess.INHERIT)
                .allowAllAccess(true) //
                // allow class lookup like "org.slf4j.LoggerFactory" from inline scripts
                .hostClassLoader(getClass().getClassLoader()) //
                // allow running Python native extensions
                .allowNativeAccess(true) //
                // allow experimental options
                .allowExperimentalOptions(true) //
                // choose the backend for the POSIX module
                .option(PYTHON_OPTION_POSIXMODULEBACKEND, "java") //
                // Force to automatically import site.py module, to make Python packages available
                .option(PYTHON_OPTION_FORCEIMPORTSITE, Boolean.toString(true)) //
                // make sure the TopLevelExceptionHandler calls the excepthook to print Python exceptions
                .option(PYTHON_OPTION_ALWAYSRUNEXCEPTHOOK, Boolean.toString(true)) //
                // emulate jython behavior (will slowdown the engine)
                .option(PYTHON_OPTION_EMULATEJYTHON,
                        String.valueOf(pythonScriptEngineConfiguration.isJythonEmulation()))

                // Set python path to point to sources stored in
                .option(PYTHON_OPTION_PYTHONPATH, PythonScriptEngineConfiguration.PYTHON_LIB_PATH.toString()
                        + File.pathSeparator + PythonScriptEngineConfiguration.PYTHON_DEFAULT_PATH.toString());

        if (this.pythonScriptEngineConfiguration.isVEnvEnabled()) {
            Path venvExecutable = this.pythonScriptEngineConfiguration.getVEnvExecutable();
            contextConfig = contextConfig.option(PYTHON_OPTION_EXECUTABLE, venvExecutable.toString());
            // Path venvPath = this.pythonScriptEngineConfiguration.getVEnvDirectory();
            // .option(PYTHON_OPTION_PYTHONHOME, venvPath.toString()) //
            // .option(PYTHON_OPTION_SYSPREFIX, venvPath.toString()) //

            if (this.pythonScriptEngineConfiguration.isNativeModulesEnabled()) {
                contextConfig = contextConfig.option(PYTHON_OPTION_NATIVEMODULES, Boolean.toString(true)) //
                        .option(PYTHON_OPTION_ISOLATENATIVEMODULES, Boolean.toString(true));
            }
        }

        if (pythonScriptEngineConfiguration.isCachingEnabled()) {
            contextConfig.option(PYTHON_OPTION_DONTWRITEBYTECODEFLAG, Boolean.toString(false)) //
                    .option(PYTHON_OPTION_CACHEDIR, pythonScriptEngineConfiguration.getBytecodeDirectory().toString());
        } else {
            contextConfig.option(PYTHON_OPTION_DONTWRITEBYTECODEFLAG, Boolean.toString(true)) //
                    // causes the interpreter to always assume hash-based pycs are valid
                    .option(PYTHON_OPTION_CHECKHASHPYCSMODE, "never");
        }

        delegate = GraalPythonScriptEngine.create(ENGINE, contextConfig);
    }

    @Override
    protected void beforeInvocation() {
        lock.lock();
        logger.debug("Lock acquired before invocation.");

        if (initialized) {
            return;
        }

        logger.debug("Initializing GraalPython script engine...");

        ScriptContext ctx = getScriptContext();

        // these are added post-construction, so we need to fetch them late
        String engineIdentifier = (String) ctx.getAttribute(CONTEXT_KEY_ENGINE_IDENTIFIER);
        if (engineIdentifier != null) {
            ScriptExtensionAccessor scriptExtensionAccessor = (ScriptExtensionAccessor) ctx
                    .getAttribute(CONTEXT_KEY_EXTENSION_ACCESSOR);
            if (scriptExtensionAccessor == null) {
                throw new IllegalStateException("Failed to retrieve script extension accessor from engine bindings");
            }

            Consumer<String> scriptDependencyListener = (Consumer<String>) ctx
                    .getAttribute(CONTEXT_KEY_DEPENDENCY_LISTENER);
            if (scriptDependencyListener == null) {
                logger.warn(
                        "Failed to retrieve script script dependency listener from engine bindings. Script dependency tracking will be disabled.");
            }
            this.scriptDependencyListener = scriptDependencyListener;

            if (pythonScriptEngineConfiguration.isScopeEnabled()) {
                // Wrap the "import" function to also allow loading modules from the ScriptExtensionModuleProvider
                BiFunction<String, List<String>, Object> wrapImportFn = (name,
                        fromlist) -> scriptExtensionModuleProvider
                                .locatorFor(delegate.getPolyglotContext(), engineIdentifier, scriptExtensionAccessor)
                                .locateModule(name, fromlist);
                delegate.getBindings(ScriptContext.ENGINE_SCOPE).put(ScriptExtensionModuleProvider.IMPORT_PROXY_NAME,
                        wrapImportFn);
                try {
                    String wrapperContent = new String(
                            Files.readAllBytes(PythonScriptEngineConfiguration.PYTHON_WRAPPER_FILE_PATH));
                    delegate.getPolyglotContext()
                            .eval(Source
                                    .newBuilder(GraalPythonScriptEngine.LANGUAGE_ID, wrapperContent,
                                            PythonScriptEngineConfiguration.PYTHON_WRAPPER_FILE_PATH.toString())
                                    .build());

                    // inject scope, Registry and logger
                    if (!pythonScriptEngineConfiguration.isInjection(PythonScriptEngineConfiguration.INJECTION_DISABLED)
                            && (ctx.getAttribute(CONTEXT_KEY_SCRIPT_FILENAME) == null || pythonScriptEngineConfiguration
                                    .isInjection(PythonScriptEngineConfiguration.INJECTION_ENABLED_FOR_ALL_SCRIPTS))) {
                        String injectionContent = "import scope\nfrom openhab import Registry, logger";
                        delegate.getPolyglotContext().eval(
                                Source.newBuilder(GraalPythonScriptEngine.LANGUAGE_ID, injectionContent, "<generated>")
                                        .build());
                    }
                } catch (IOException e) {
                    logger.error("Failed to inject import wrapper", e);
                    throw new IllegalArgumentException("Failed to inject import wrapper", e);
                }
            }
        }

        InputStream input = (InputStream) ctx.getAttribute(CONTEXT_KEY_ENGINE_LOGGER_INPUT);
        if (input != null) {
            scriptInputStream.setInputStream(input);
        }

        OutputStream output = (OutputStream) ctx.getAttribute(CONTEXT_KEY_ENGINE_LOGGER_OUTPUT);
        if (output != null) {
            scriptOutputStream.setOutputStream(output);
            scriptErrorStream.setOutputStream(output);
        } else {
            // logger initialization, for non file based scripts, has to be delayed, because ruleUID is not
            // available yet
            if (ctx.getAttribute(CONTEXT_KEY_SCRIPT_FILENAME) == null) {
                Runnable wrapperLoggerFn = () -> setScriptLogger();
                delegate.getBindings(ScriptContext.ENGINE_SCOPE).put(LOGGER_INIT_NAME, wrapperLoggerFn);
            } else {
                setScriptLogger();
            }
        }

        initialized = true;
    }

    @Override
    protected String beforeInvocation(String source) {
        source = super.beforeInvocation(source);

        // Happens for Transform and UI based rules (eval and compile)
        // and has to be evaluate every time, because of changing and late injected ruleUID
        if (delegate.getBindings(ScriptContext.ENGINE_SCOPE).get(LOGGER_INIT_NAME) != null) {
            return LOGGER_INIT_NAME + "()\n" + source;
        }

        return source;
    }

    @Override
    protected Object afterInvocation(Object obj) {
        lock.unlock();
        logger.debug("Lock released after invocation.");
        return super.afterInvocation(obj);
    }

    @Override
    protected Exception afterThrowsInvocation(Exception e) {
        // OPS4J Pax Logging holds a reference to the exception, which causes the PythonScriptEngine to not be
        // removed from heap by garbage collection and causing a memory leak.
        // Therefore, don't pass the exceptions itself to the logger, but only their message!
        if (e instanceof ScriptException) {
            // PolyglotException will always be wrapped into ScriptException and they will be visualized in
            // org.openhab.core.automation.module.script.internal.ScriptEngineManagerImpl
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to execute script (PolyglotException): {}", stringifyThrowable(e.getCause()));
            }
        } else if (e.getCause() instanceof IllegalArgumentException) {
            logger.error("Failed to execute script (IllegalArgumentException): {}", stringifyThrowable(e.getCause()));
        }

        lock.unlock();

        return super.afterThrowsInvocation(e);
    }

    @Override
    // collect JSR223 (scope) variables separately, because they are delivered via 'import scope'
    public void put(String key, Object value) {
        if (CONTEXT_KEY_SCRIPT_FILENAME.equals(key)) {
            super.put(key, value);
        } else {
            // use a custom lifecycleTracker to handle dispose hook before polyglot context is closed
            // original lifecycleTracker is handling it when polyglot context is already closed
            if ("lifecycleTracker".equals(key)) {
                value = lifecycleTracker;
            }
            if (pythonScriptEngineConfiguration.isScopeEnabled()) {
                scriptExtensionModuleProvider.put(key, value);
            } else {
                super.put(key, value);
            }
        }
    }

    @Override
    public void lock() {
        lock.lock();
        logger.debug("Lock acquired.");
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        lock.lockInterruptibly();
    }

    @Override
    public boolean tryLock() {
        boolean acquired = lock.tryLock();
        logger.debug("{}", acquired ? "Lock acquired." : "Lock not acquired.");
        return acquired;
    }

    @Override
    public boolean tryLock(long l, TimeUnit timeUnit) throws InterruptedException {
        boolean acquired = lock.tryLock(l, timeUnit);
        logger.debug("{}", acquired ? "Lock acquired." : "Lock not acquired.");
        return acquired;
    }

    @Override
    public void unlock() {
        lock.unlock();
        logger.debug("Lock released.");
    }

    @Override
    public void close() throws Exception {
        lock.lock();

        if (!closed) {
            try {
                this.lifecycleTracker.dispose();
            } catch (Exception e) {
                logger.warn("Ignoreable exception during dispose: {}", stringifyThrowable(e));
            }
            logger.debug("Engine disposed.");

            try {
                super.close();
            } catch (Exception e) {
                logger.warn("Ignoreable exception during close: {}", stringifyThrowable(e));
            }

            logger.debug("Engine closed.");

            closed = true;
        } else {
            logger.debug("Engine already disposed and closed.");
        }

        lock.unlock();
    }

    @Override
    public Condition newCondition() {
        return lock.newCondition();
    }

    @Override
    public ScriptContext getContext() {
        return getScriptContext();
    }

    /**
     * Initializes the logger.
     * This cannot be done on script engine creation because the context variables are not yet initialized.
     * Therefore, the logger needs to be initialized on the first use after script engine creation.
     */
    private void setScriptLogger() {
        ScriptContext ctx = getScriptContext();
        Object fileName = ctx.getAttribute(CONTEXT_KEY_SCRIPT_FILENAME);
        Object ruleUID = ctx.getAttribute("ruleUID");
        Object ohEngineIdentifier = ctx.getAttribute(CONTEXT_KEY_ENGINE_IDENTIFIER);

        String identifier = "stack";
        if (fileName != null) {
            identifier = fileName.toString().replaceAll("^.*[/\\\\]", "");
        } else if (ruleUID != null) {
            identifier = ruleUID.toString();
        } else if (ohEngineIdentifier != null) {
            if (ohEngineIdentifier.toString().startsWith(OPENHAB_TRANSFORMATION_SCRIPT)) {
                identifier = ohEngineIdentifier.toString().replaceAll(OPENHAB_TRANSFORMATION_SCRIPT, "transformation.");
            }
        }

        Logger scriptLogger = LoggerFactory.getLogger("org.openhab.automation.pythonscripting." + identifier);

        scriptOutputStream.setOutputStream(new ContextOutputLogger(scriptLogger, Level.INFO));
        scriptErrorStream.setOutputStream(new ContextOutputLogger(scriptLogger, Level.ERROR));
    }

    private ScriptContext getScriptContext() {
        ScriptContext ctx = delegate.getContext();
        if (ctx == null) {
            throw new IllegalStateException("Failed to retrieve script context");
        }
        return ctx;
    }

    private String stringifyThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        StackTraceElement[] stackTraceElements = throwable.getStackTrace();
        String stackTrace = Arrays.stream(stackTraceElements).limit(STACK_TRACE_LENGTH)
                .map(t -> "        at " + t.toString()).collect(Collectors.joining(System.lineSeparator()))
                + System.lineSeparator() + "        ... " + stackTraceElements.length + " more";
        return (message != null) ? message + System.lineSeparator() + stackTrace : stackTrace;
    }

    private static Set<String> transformArrayToSet(Value value) {
        Set<String> set = new HashSet<>();
        for (int i = 0; i < value.getArraySize(); ++i) {
            Value element = value.getArrayElement(i);
            set.add(element.asString());
        }
        return set;
    }
}
