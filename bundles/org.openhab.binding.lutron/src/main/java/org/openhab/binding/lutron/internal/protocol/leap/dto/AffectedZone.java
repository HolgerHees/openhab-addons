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
package org.openhab.binding.lutron.internal.protocol.leap.dto;

import com.google.gson.annotations.SerializedName;

/**
 * LEAP AffectedZone Object
 *
 * @author Bob Adair - Initial contribution
 */
public class AffectedZone {
    @SerializedName("href")
    public String href;
    @SerializedName("Zone")
    public Href zone = new Href();

    public AffectedZone() {
    }
}
