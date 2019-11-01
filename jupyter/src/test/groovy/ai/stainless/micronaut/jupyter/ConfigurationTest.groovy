package ai.stainless.micronaut.jupyter

import io.micronaut.context.ApplicationContext
import io.micronaut.context.env.Environment
import spock.lang.Specification

class ConfigurationTest extends Specification {

    def "can be disabled"() {
        given:
        // create application context
        ApplicationContext applicationContext = ApplicationContext.run([
            'jupyter.enabled': false
        ] as Map, Environment.TEST)

        expect:
        // beans should NOT have been created
        !applicationContext.containsBean(InstallKernel)
        !applicationContext.containsBean(KernelEndpoint)
        !applicationContext.containsBean(KernelManager)

        cleanup:
        applicationContext.close()
    }

}
