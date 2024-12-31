package org.openhab.automation.pythonscripting.internal.graal;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.script.Bindings;
import javax.script.ScriptContext;

import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.TypeLiteral;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyObject;
import org.openhab.automation.pythonscripting.internal.graal.GraalPythonScriptEngine.MagicBindingsOptionSetter;

final class GraalPythonBindings extends AbstractMap<String, Object> implements Bindings, AutoCloseable {
    private static final String SCRIPT_CONTEXT_GLOBAL_BINDINGS_IMPORT_FUNCTION_NAME = "importScriptEngineGlobalBindings";

    private static final TypeLiteral<Map<String, Object>> STRING_MAP = new TypeLiteral<Map<String, Object>>() {
    };

    private Context context;
    private Map<String, Object> global;
    private Value deleteProperty;
    private Value clear;
    private Context.Builder contextBuilder;
    // ScriptContext of the ScriptEngine where these bindings form ENGINE_SCOPE bindings
    private ScriptContext engineScriptContext;

    GraalPythonBindings(Context.Builder contextBuilder, ScriptContext scriptContext) {
        this.contextBuilder = contextBuilder;
        this.engineScriptContext = scriptContext;
    }

    GraalPythonBindings(Context context, ScriptContext scriptContext) {
        this.context = context;
        initGlobal();
        this.engineScriptContext = scriptContext;
    }

    private void requireContext() {
        if (context == null) {
            initContext();
        }
    }

    private void initContext() {
        context = GraalPythonScriptEngine.createDefaultContext(contextBuilder);
        initGlobal();
    }

    private void initGlobal() {
        this.global = GraalPythonScriptEngine.evalInternal(context, "globals()").as(STRING_MAP);
    }

    private Value deletePropertyFunction() {
        if (this.deleteProperty == null) {
            this.deleteProperty = GraalPythonScriptEngine.evalInternal(context, //
                    "def delete_property(obj, prop):\n" //
                            + "    if prop in obj:\n" //
                            + "        del obj[prop]");
        }
        return this.deleteProperty;
    }

    private Value clearFunction() {
        if (this.clear == null) {
            this.clear = GraalPythonScriptEngine.evalInternal(context, //
                    "def delete_properties(obj):\n" //
                            + "    for prop in list(obj.keys()):" //
                            + "        del obj[prop]");
        }
        return this.clear;
    }

    @Override
    public Object put(String name, Object v) {
        checkKey(name);
        if (name.startsWith(GraalPythonScriptEngine.MAGIC_OPTION_PREFIX)) {
            if (context == null) {
                MagicBindingsOptionSetter optionSetter = GraalPythonScriptEngine.MAGIC_BINDINGS_OPTION_MAP.get(name);
                if (optionSetter == null) {
                    throw new IllegalArgumentException("unkown graal-py option \"" + name + "\"");
                } else {
                    contextBuilder = optionSetter.setOption(contextBuilder, v);
                    return true;
                }
            } else {
                throw magicOptionContextInitializedError(name);
            }
        }
        requireContext();
        return global.put(name, v);
    }

    @Override
    public void clear() {
        if (context != null) {
            clearFunction().execute(global);
        }
    }

    @Override
    public Object get(Object key) {
        checkKey((String) key);
        requireContext();
        if (engineScriptContext != null) {
            importGlobalBindings(engineScriptContext);
        }
        return global.get(key);
    }

    private static void checkKey(String key) {
        Objects.requireNonNull(key, "key can not be null");
        if (key.isEmpty()) {
            throw new IllegalArgumentException("key can not be empty");
        }
    }

    @Override
    public Object remove(Object key) {
        requireContext();
        Object prev = get(key);
        deletePropertyFunction().execute(global, key);
        return prev;
    }

    public Context getContext() {
        requireContext();
        return context;
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        requireContext();
        return global.entrySet();
    }

    @Override
    public void close() {
        if (context != null) {
            context.close();
        }
    }

    private static IllegalStateException magicOptionContextInitializedError(String name) {
        return new IllegalStateException(
                String.format("failed to set graal-py option \"%s\": py context is already initialized", name));
    }

    void importGlobalBindings(ScriptContext scriptContext) {
        Bindings globalBindings = scriptContext.getBindings(ScriptContext.GLOBAL_SCOPE);
        if (globalBindings != null && !globalBindings.isEmpty() && this != globalBindings) {
            ProxyObject bindingsProxy = ProxyObject.fromMap(Collections.unmodifiableMap(globalBindings));
            getContext().getBindings("py").getMember(SCRIPT_CONTEXT_GLOBAL_BINDINGS_IMPORT_FUNCTION_NAME)
                    .execute(bindingsProxy);
        }
    }

    void updateEngineScriptContext(ScriptContext scriptContext) {
        engineScriptContext = scriptContext;
    }

}
