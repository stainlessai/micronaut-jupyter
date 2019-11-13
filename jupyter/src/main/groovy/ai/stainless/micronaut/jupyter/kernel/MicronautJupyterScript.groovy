package ai.stainless.micronaut.jupyter.kernel

import groovy.util.logging.Slf4j
import io.micronaut.context.ApplicationContext

@Slf4j
public abstract class MicronautJupyterScript extends Script {

    private ApplicationContext applicationContext

    public MicronautJupyterScript () {
        log.debug "Creating new jupyter script instance with binding: $binding"
        setBindingInstanceVariables(binding)
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext
    }

    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext
    }

    private void setBindingInstanceVariables (Binding scriptBinding) {
        // set instance variables from binding
        if (binding.hasVariable("_boundApplicationContext")) {
            Object v = binding.getVariable("_boundApplicationContext")
            log.debug("Setting applicationContext to $v")
            applicationContext = v as ApplicationContext
        }
        else {
            log.warn "No applicationContext found in binding: $scriptBinding"
        }
    }

    @Override
    public void setBinding(Binding newBinding) {
        log.debug "Updating script binding to: $newBinding"
        //update binding
        super.setBinding(newBinding)
        //update variables
        setBindingInstanceVariables(binding)
    }

    public <T> T service (Class<T> beanType) {
        return applicationContext.getBean(beanType)
    }

}
