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

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import javax.script.ScriptEngine;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.automation.pythonscripting.internal.fs.watch.PythonDependencyTracker;
import org.openhab.core.automation.module.script.ScriptDependencyTracker;
import org.openhab.core.automation.module.script.ScriptEngineFactory;
import org.openhab.core.config.core.ConfigurableService;
import org.openhab.core.i18n.TimeZoneProvider;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This is an implementation of {@link ScriptEngineFactory} for Python.
 *
 * @author Holger Hees - Initial contribution
 * @author Jeff James - Initial contribution
 */
@Component(service = { ScriptEngineFactory.class, PythonScriptEngineFactory.class }, //
        configurationPid = "org.openhab.automation.pythonscripting", //
        property = Constants.SERVICE_PID + "=org.openhab.automation.pythonscripting")
@ConfigurableService(category = "automation", label = "Python Scripting", description_uri = PythonScriptEngineFactory.CONFIG_DESCRIPTION_URI)
@NonNullByDefault
public class PythonScriptEngineFactory implements ScriptEngineFactory {
    private final Logger logger = LoggerFactory.getLogger(PythonScriptEngineFactory.class);

    public static final String CONFIG_DESCRIPTION_URI = "automation:pythonscripting";
    public static final String SCRIPT_TYPE = "application/x-python3";

    private final List<String> scriptTypes = Arrays.asList("py", SCRIPT_TYPE);
    private final PythonDependencyTracker pythonDependencyTracker;
    private final PythonScriptEngineConfiguration pythonScriptEngineConfiguration;

    @Activate
    public PythonScriptEngineFactory(final @Reference PythonDependencyTracker pythonDependencyTracker,
            final @Reference TimeZoneProvider timeZoneProvider, Map<String, Object> config) {
        logger.debug("Loading PythonScriptEngineFactory");

        this.pythonDependencyTracker = pythonDependencyTracker;
        this.pythonScriptEngineConfiguration = new PythonScriptEngineConfiguration();

        String defaultTimezone = ZoneId.systemDefault().getId();
        String providerTimezone = timeZoneProvider.getTimeZone().getId();
        if (!defaultTimezone.equals(providerTimezone)) {
            String MSG = """
                    User timezone '{}' is different than openhab regional timezone '{}'.
                    Please double check that your startup setting of '-Duser.timezone=' is matching your openhab regional setting
                    Pythonscripting is running with timezone '{}'""";
            logger.warn(MSG, defaultTimezone, providerTimezone, defaultTimezone);
            // System.setProperty("user.timezone", "Australia/Tasmania");
        }

        modified(config);
    }

    @Deactivate
    public void cleanup() {
        logger.debug("Unloading PythonScriptEngineFactory");
    }

    @Modified
    protected void modified(Map<String, Object> config) {
        this.pythonScriptEngineConfiguration.update(config, this);
    }

    @Override
    public List<String> getScriptTypes() {
        return scriptTypes;
    }

    @Override
    public void scopeValues(ScriptEngine scriptEngine, Map<String, Object> scopeValues) {
        for (Entry<String, Object> entry : scopeValues.entrySet()) {
            scriptEngine.put(entry.getKey(), entry.getValue());
        }
    }

    @Override
    public @Nullable ScriptEngine createScriptEngine(String scriptType) {
        if (!scriptTypes.contains(scriptType)) {
            return null;
        }
        return new PythonScriptEngine(pythonDependencyTracker, pythonScriptEngineConfiguration);
    }

    @Override
    public @Nullable ScriptDependencyTracker getDependencyTracker() {
        return pythonDependencyTracker;
    }

    public PythonScriptEngineConfiguration getConfiguration() {
        return this.pythonScriptEngineConfiguration;
    }
}
