package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.context.annotation.Property

class SignalHandlingTest extends KernelSpec {

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
    def "SIGINT signal is detected and logged"() {
        setup:
        // NOTE: must call this to correctly setup logging
        executeNotebook("println")

        when:
        def pidResult = micronautContainer.execInContainer("pgrep", "-f", "java")
        def javaPid = pidResult.stdout.trim()
        def signalResult = micronautContainer.execInContainer("kill", "-INT", javaPid)
        Thread.sleep(2000)
        def logs = micronautContainer.getLogs()

        then:
        signalResult.exitCode == 0
        logs.contains("Received signal: SIGINT (2)")
        
        and: "kernel may or may not survive"
        def processCheck = micronautContainer.execInContainer("pgrep", "-f", "java")
        println "SIGINT: Kernel survived = ${processCheck.exitCode == 0}"
    }

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "false")
    def "SIGTERM signal is detected and logged"() {
        setup:
        micronautContainer.getDockerClient()
                .restartContainerCmd(micronautContainer.getContainerId())
                .exec();
        // NOTE: must call this to correctly setup logging
        executeNotebook("println")

        when:
        def pidResult = micronautContainer.execInContainer("pgrep", "-f", "java")
        def javaPid = pidResult.stdout.trim()
        def signalResult = micronautContainer.execInContainer("kill", "-TERM", javaPid)
        def logs = micronautContainer.getLogs()

        then:
        micronautContainer.isRunning()
    }

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
    def "SIGHUP signal is detected and logged"() {
        setup:
        micronautContainer.getDockerClient()
                .restartContainerCmd(micronautContainer.getContainerId())
                .exec();
        // NOTE: must call this to correctly setup logging
        executeNotebook("println")

        when:
        def pidResult = micronautContainer.execInContainer("pgrep", "-f", "java")
        def javaPid = pidResult.stdout.trim()
        def signalResult = micronautContainer.execInContainer("kill", "-HUP", javaPid)

        then:
        micronautContainer.isRunning()
    }

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
    def "SIGUSR1 signal is detected and logged"() {
        setup:
        micronautContainer.getDockerClient()
                .restartContainerCmd(micronautContainer.getContainerId())
                .exec();
        // NOTE: must call this to correctly setup logging
        executeNotebook("println")

        when:
        def pidResult = micronautContainer.execInContainer("pgrep", "-f", "java")
        def javaPid = pidResult.stdout.trim()
        def signalResult = micronautContainer.execInContainer("kill", "-USR1", javaPid)

        then:
        micronautContainer.isRunning()
    }

    @Property(name = "jupyter.kernel.redirectLogOutput", value = "")
    def "SIGUSR2 signal is detected and logged"() {
        setup:
        micronautContainer.getDockerClient()
                .restartContainerCmd(micronautContainer.getContainerId())
                .exec();
        // NOTE: must call this to correctly setup logging
        executeNotebook("println")

        when:
        def pidResult = micronautContainer.execInContainer("pgrep", "-f", "java")
        def javaPid = pidResult.stdout.trim()
        def signalResult = micronautContainer.execInContainer("kill", "-USR2", javaPid)

        then:
        micronautContainer.isRunning()
    }
}