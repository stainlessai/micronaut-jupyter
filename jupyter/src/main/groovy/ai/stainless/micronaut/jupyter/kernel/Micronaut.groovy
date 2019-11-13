package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.BeakerXCommRepository
import com.twosigma.beakerx.CommRepository
import com.twosigma.beakerx.NamespaceClient
import com.twosigma.beakerx.evaluator.Evaluator
import com.twosigma.beakerx.groovy.evaluator.GroovyEvaluator
import com.twosigma.beakerx.groovy.kernel.Groovy
import com.twosigma.beakerx.groovy.kernel.GroovyBeakerXServer
import com.twosigma.beakerx.kernel.BeakerXJson
import com.twosigma.beakerx.kernel.BeakerXJsonConfig
import com.twosigma.beakerx.kernel.CacheFolderFactory
import com.twosigma.beakerx.kernel.CloseKernelAction
import com.twosigma.beakerx.kernel.EnvCacheFolderFactory
import com.twosigma.beakerx.kernel.KernelConfigurationFile
import com.twosigma.beakerx.kernel.KernelRunner
import com.twosigma.beakerx.kernel.Utils
import com.twosigma.beakerx.kernel.magic.command.MagicCommandConfiguration
import com.twosigma.beakerx.kernel.magic.command.MagicCommandConfigurationImpl
import com.twosigma.beakerx.kernel.restserver.BeakerXServer
import com.twosigma.beakerx.kernel.restserver.impl.GetUrlArgHandler
import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext

@Slf4j
public class Micronaut extends Groovy {

    private TrackableKernelSocketsFactory kernelSocketsFactory
    private MicronautEvaluator evaluator

    private ApplicationContext applicationContext

    public Micronaut (
        final String id,
        final MicronautEvaluator evaluator,
        TrackableKernelSocketsFactory kernelSocketsFactory,
        CloseKernelAction closeKernelAction,
        CacheFolderFactory cacheFolderFactory,
        CommRepository commRepository,
        BeakerXServer beakerXServer,
        MagicCommandConfiguration magicCommandConfiguration,
        BeakerXJson beakerXJson
    ) {
        super(
            id,
            evaluator,
            kernelSocketsFactory,
            closeKernelAction,
            cacheFolderFactory,
            commRepository,
            beakerXServer,
            magicCommandConfiguration,
            beakerXJson
        )
        //store properties
        this.kernelSocketsFactory = kernelSocketsFactory
        this.evaluator = evaluator
    }

    public void init () {
        //load evaluator
        evaluator.kernel = this
        evaluator.init()
    }

    public void kill () {
        log.info "Killing kernel now!"
        // close sockets factory instances
        kernelSocketsFactory.instances.each {
            try {
                it.shutdown()
            }
            catch (NoSuchMethodError e) { }
        }
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext
    }

    /*
     * Create groovy kernel.
     * Use implementation of logic from Groovy.main() method in BeakerX project.
     * License from BeakerX pasted below.
     */
    public static Micronaut createKernel (final String[] args) {

        log.info "Spinning up new Micronaut kernel"
        log.info "Received args: $args"

        // create kernel close handler
        CloseKernelAction closeKernelAction = {
            // time to close the kernel
            throw new KernelExitException("Kernel close action received, interrupting kernel.")
        } as CloseKernelAction

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

        String id = Utils.uuid();
        KernelConfigurationFile configurationFile = new KernelConfigurationFile(args);
        TrackableKernelSocketsFactory kernelSocketsFactory = new TrackableKernelSocketsFactory(
            configurationFile
        );

        BeakerXCommRepository beakerXCommRepository = new BeakerXCommRepository();
        NamespaceClient namespaceClient = NamespaceClient.create(id, configurationFile, beakerXCommRepository);
        MagicCommandConfiguration magicCommandTypesFactory = new MagicCommandConfigurationImpl();
        MicronautEvaluator evaluator = new MicronautEvaluator(
            id,
            getEvaluatorParameters(),
            namespaceClient,
            magicCommandTypesFactory.patterns()
        );
        return new Micronaut(
            id,
            evaluator,
            kernelSocketsFactory,
            closeKernelAction,
            new EnvCacheFolderFactory(),
            beakerXCommRepository,
            new GroovyBeakerXServer(new GetUrlArgHandler(namespaceClient)),
            magicCommandTypesFactory,
            new BeakerXJsonConfig()
        );

        /*
         * End BeakerX implementation.
         */

    }

    public static void main (final String[] args) {

        KernelRunner.run({
            return createKernel(args)
        })

    }

}
