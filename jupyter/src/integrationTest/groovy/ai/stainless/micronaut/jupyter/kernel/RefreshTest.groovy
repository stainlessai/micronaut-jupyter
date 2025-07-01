package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.context.annotation.Property
import io.micronaut.runtime.context.scope.refresh.RefreshEvent

class RefreshTest extends KernelSpec {

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
    def "Refresh endpoint is inaccessible"() {
        setup:
        // First execute a notebook to initialize kernel and generate baseline logs
        executeNotebook("println")

        when:
        print "refreshing..."
        // Trigger a refresh event by making a POST request to the refresh endpoint
        def refreshResult = micronautContainer.execInContainer("curl", "-X", "POST", "http://localhost:8080/refresh")
        Thread.sleep(2000) // Allow time for refresh to complete
        def logs = micronautContainer.getLogs()

        then:
        refreshResult.stdout.contains("Unauthorized")
//        print refreshResult
//        print refreshResult.exitCode
//        print refreshResult.stderr
//        print refreshResult.stdout
//        refreshResult.exitCode == 0
//        // Verify that PreDestroy method was called
//        logs.contains("Destroying KernelManager")
//        // Verify that new KernelManager was initialized
//        logs.contains("Initializing KernelManager")
//
//        and: "container should still be running after refresh"
//        micronautContainer.isRunning()
    }

//    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
//    def "Kernel instances are cleaned up on refresh"() {
//        setup:
//        // Execute a notebook to create kernel instances
//        executeNotebook("println")
//
//        when:
//        // Trigger refresh
//        def refreshResult = micronautContainer.execInContainer("curl", "-X", "POST", "http://localhost:8080/refresh")
//        Thread.sleep(2000)
//        def logs = micronautContainer.getLogs()
//
//        then:
//        refreshResult.exitCode == 0
//        // Verify kernel cleanup during destroy
//        logs.contains("Killing all kernels")
//        logs.contains("Destroying KernelManager")
//        // Verify new initialization
//        logs.contains("KernelManager initialized successfully")
//    }
}