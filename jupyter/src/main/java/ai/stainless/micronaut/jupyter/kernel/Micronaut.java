package ai.stainless.micronaut.jupyter.kernel;

import com.twosigma.beakerx.BeakerXCommRepository;
import com.twosigma.beakerx.CommRepository;
import com.twosigma.beakerx.NamespaceClient;
import com.twosigma.beakerx.evaluator.ClasspathScannerImpl;
import com.twosigma.beakerx.evaluator.BxInspect;
import com.twosigma.beakerx.groovy.kernel.Groovy;
import com.twosigma.beakerx.groovy.kernel.GroovyBeakerXServer;
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

    /**
     * Get the stream handler, creating one if it doesn't exist
     *
     * @return The StandardStreamHandler instance
     */
    public StandardStreamHandler getStreamHandler() {
        if (streamHandler == null) {
            log.warn("StreamHandler was null when getStreamHandler was called, creating new instance");
            streamHandler = createDefaultStreamHandler();
        }
        return streamHandler;
    }

    /**
     * Set the stream handler
     *
     * @param streamHandler The StandardStreamHandler to use
     */
    public void setStreamHandler(StandardStreamHandler streamHandler) {
        this.streamHandler = streamHandler;
    }

    /**
     * Create a default StandardStreamHandler
     *
     * @return New StandardStreamHandler instance
     */
    private StandardStreamHandler createDefaultStreamHandler() {
        // Create handler without setting redirectLogOutput - initialize with default values
        StandardStreamHandler handler = new StandardStreamHandler();
        handler.init();
        return handler;
    }

    /**
     * Initialize the kernel
     */
    public void init() {
        log.info("Initializing Micronaut kernel");

        // Ensure we have a valid stream handler
        if (streamHandler == null) {
            log.warn("No StreamHandler provided, creating default");
            if (applicationContext != null) {
                try {
                    streamHandler = applicationContext.getBean(StandardStreamHandler.class);
                    log.info("Retrieved StandardStreamHandler from application context");
                } catch (Exception e) {
                    log.warn("Could not get StandardStreamHandler from context, creating new instance");
                    log.debug("Exception when trying to get StandardStreamHandler from context", e);
                    streamHandler = createDefaultStreamHandler();
                }
            } else {
                streamHandler = createDefaultStreamHandler();
            }
        }

        //load evaluator
        evaluator.setKernel(this);
        evaluator.init();

        Kernel.showNullExecutionResult = false;

        log.info("Micronaut kernel initialized successfully");
    }

    /**
     * Kill this kernel
     */
    public void kill() {
        log.info("Killing kernel now!");
        // close sockets factory instances
        for (KernelSockets it : kernelSocketsFactory.getInstances()) {
            try {
                ((CloseableKernelSocketsZMQ) it).shutdown();
            } catch (NoSuchMethodError e) {
                log.error(e.toString());
            } catch (Exception e) {
                log.error("Error shutting down kernel socket", e);
            }
        }

        // Clean up stream handler
        try {
            if (streamHandler != null) {
                streamHandler.restore();
            }
        } catch (Exception e) {
            log.error("Error restoring streams during kernel shutdown", e);
        }
    }

    /*
     * Create groovy kernel.
     * Use implementation of logic from Groovy.main() method in BeakerX project.
     * License from BeakerX pasted below.
     */
    public static Micronaut createKernel(final String[] args) {
        log.info("Spinning up new Micronaut kernel");

        if (args != null && args.length > 0) {
            log.info("Received args: {}", String.join(", ", args));
            
            // Print contents of the first arg if it's a file path
            String filePath = args[0];
            try {
                java.io.File file = new java.io.File(filePath);
                log.info("Contents of file '{}': {}", filePath, java.nio.file.Files.readString(java.nio.file.Path.of(filePath)));
            } catch (Exception e) {
                log.warn("Could not read file '{}': {}", filePath, e.getMessage());
            }
        } else {
            log.warn("No args provided to kernel");
        }

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
    }

    public static void main(final String[] args) {
        new BxKernelRunner().run(() -> {
            return Micronaut.createKernel(args);
        });
    }
}