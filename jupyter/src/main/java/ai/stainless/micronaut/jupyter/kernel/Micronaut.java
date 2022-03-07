package ai.stainless.micronaut.jupyter.kernel;

import com.twosigma.beakerx.BeakerXCommRepository;
import com.twosigma.beakerx.CommRepository;
import com.twosigma.beakerx.NamespaceClient;
import com.twosigma.beakerx.evaluator.ClasspathScannerImpl;
import com.twosigma.beakerx.evaluator.BxInspect;
import com.twosigma.beakerx.groovy.kernel.Groovy;
import com.twosigma.beakerx.groovy.kernel.GroovyBeakerXServer;
import com.twosigma.beakerx.inspect.Inspect;
import com.twosigma.beakerx.kernel.*;
import com.twosigma.beakerx.kernel.magic.autocomplete.MagicCommandAutocompletePatternsImpl;
import com.twosigma.beakerx.kernel.magic.command.FileServiceImpl;
import com.twosigma.beakerx.kernel.magic.command.MagicCommandConfiguration;
import com.twosigma.beakerx.kernel.magic.command.MagicCommandConfigurationImpl;
import com.twosigma.beakerx.kernel.magic.command.MavenJarResolverServiceImpl;
import com.twosigma.beakerx.kernel.restserver.BeakerXServer;
import com.twosigma.beakerx.kernel.restserver.impl.GetUrlArgHandler;
import groovy.util.logging.Slf4j;
import io.micronaut.context.ApplicationContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.twosigma.beakerx.kernel.Utils.uuid;

@Slf4j
public class Micronaut extends Groovy {

    public static final Logger log = LoggerFactory.getLogger(Micronaut.class);
    private TrackableKernelSocketsFactory kernelSocketsFactory;
    private MicronautEvaluator evaluator;
    private ApplicationContext applicationContext;
    private StandardStreamHandler streamHandler;

    public Micronaut(
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
                new Configuration(kernelSocketsFactory,
                        closeKernelAction,
                        cacheFolderFactory,
                        new CustomMagicCommandsEmptyImpl(),
                        commRepository,
                        beakerXServer,
                        magicCommandConfiguration,
                        beakerXJson,
                        new MicronautRuntimetoolsImpl())
        );
        //store properties
        this.kernelSocketsFactory = kernelSocketsFactory;
        this.evaluator = evaluator;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public void setApplicationContext(ApplicationContext ctx) {
        this.applicationContext = ctx;
    }

    public StandardStreamHandler getStreamHandler() {
        return streamHandler;
    }

    public void setStreamHandler(StandardStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    public void init() {
        //load evaluator
        evaluator.setKernel(this);
        evaluator.init();
    }

    public void kill() {
        log.info("Killing kernel now!");
        // close sockets factory instances
        for (KernelSockets it : kernelSocketsFactory.getInstances()) {
            try {
                ((ClosableKernelSocketsZMQ)it).shutdown();
            } catch (NoSuchMethodError e) {
                log.error(e.toString());
            }
        }
    }

    /*
     * Create groovy kernel.
     * Use implementation of logic from Groovy.main() method in BeakerX project.
     * License from BeakerX pasted below.
     */

    public static Micronaut createKernel(final String[] args) {

        log.info("Spinning up new Micronaut kernel");
        log.info("Received args: $args");

        // create kernel close handler
        CloseKernelAction closeKernelAction = new CloseKernelAction() {
            @Override
            public void close() {
                // time to close the kernel
                throw new RuntimeException("Kernel close action received, interrupting kernel.");
            }
        };

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

        String id = uuid();
        KernelConfigurationFile configurationFile = new KernelConfigurationFile(args);
        TrackableKernelSocketsFactory kernelSocketsFactory = new TrackableKernelSocketsFactory(
                configurationFile
        );

        BeakerXCommRepository beakerXCommRepository = new BeakerXCommRepository();
        NamespaceClient namespaceClient = NamespaceClient.create(id, configurationFile, beakerXCommRepository);
        MagicCommandConfiguration magicCommandTypesFactory = new MagicCommandConfigurationImpl(new FileServiceImpl(), new MavenJarResolverServiceImpl(), new MagicCommandAutocompletePatternsImpl());
        MicronautEvaluator evaluator = new MicronautEvaluator(id,
                id,
                getEvaluatorParameters(),
                namespaceClient,
                magicCommandTypesFactory.patterns(),
                new ClasspathScannerImpl(),
                new BxInspect(BxInspect.getInspectFile()));

        return new Micronaut(id,
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

    public static void main(final String[] args) {
        new BxKernelRunner().run(() -> {
            return Micronaut.createKernel(args);
        } );
    }

}
