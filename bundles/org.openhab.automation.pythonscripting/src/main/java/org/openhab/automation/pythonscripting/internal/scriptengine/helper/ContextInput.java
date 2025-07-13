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
package org.openhab.automation.pythonscripting.internal.scriptengine.helper;

import java.io.IOException;
import java.io.InputStream;

/**
 * ContextOutput implementation
 *
 * @author Holger Hees - Initial contribution
 */
public class ContextInput extends InputStream {
    private InputStream stream;

    public ContextInput(InputStream stream) {
        this.stream = stream;
    }

    public void setInputStream(InputStream stream) {
        this.stream = stream;
    }

    @Override
    public void close() throws IOException {
        if (stream == null) {
            return;
        }
        stream.close();
    }

    @Override
    public int read() throws IOException {
        if (stream == null) {
            return -1;
        }
        return stream.read();
    }
}
