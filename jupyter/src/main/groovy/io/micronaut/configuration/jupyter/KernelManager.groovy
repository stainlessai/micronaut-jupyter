package io.micronaut.configuration.jupyter

import com.twosigma.beakerx.groovy.kernel.Groovy
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.inject.Singleton

/**
 * Manages the Jupyter kernel instances that are created.
 */
@Singleton
public class KernelManager {

    private static final Logger log = LoggerFactory.getLogger(KernelEndpoint.class)

    public void startNewKernel (String connectionFile) {

        // start kernel in new thread
        Thread.start {

            log.info "Starting new Groovy kernel! Connection file: $connectionFile"

            Groovy.main([connectionFile] as String[])

            log.info "Groovy kernel exited, ending thread."

        }

    }

}
