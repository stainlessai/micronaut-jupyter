package io.micronaut.configuration.jupyter.kernel;

/*
 * Customized KernelSocketsFactory implementation that tracks and stores the
 * instances it creates.
 * Uses implementation of KernelSocketsFactoryImpl in BeakerX project.
 * License from BeakerX pasted below.
 */

/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import com.twosigma.beakerx.kernel.*;
import com.twosigma.beakerx.socket.KernelSocketsZMQ;

import java.util.ArrayList;

import static com.twosigma.beakerx.util.Preconditions.checkNotNull;

public class TrackableKernelSocketsFactory implements KernelSocketsFactory {

    private ConfigurationFile configurationFile;

    private ArrayList<KernelSockets> instances = new ArrayList<>();

    public TrackableKernelSocketsFactory(ConfigurationFile configurationFile) {
        this.configurationFile = checkNotNull(configurationFile);
    }

    public KernelSockets create(final KernelFunctionality kernel, final SocketCloseAction closeAction) {
        // create new ZMQ sockets instance
        KernelSockets sockets = new ClosableKernelSocketsZMQ(kernel, configurationFile.getConfig(), closeAction);
        // store this instance for later tracking
        instances.add(sockets);
        // return this instance
        return sockets;
    }

    public ArrayList<KernelSockets> getInstances() {
        return instances;
    }
}
