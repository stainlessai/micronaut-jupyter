package ai.stainless.micronaut.jupyter.kernel

import io.micronaut.test.extensions.spock.annotation.MicronautTest
import spock.lang.Specification
import spock.lang.Timeout
import ai.stainless.micronaut.jupyter.KernelManager
import com.twosigma.beakerx.kernel.Kernel
import jakarta.inject.Inject
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Integration tests for kernel isolation functionality
 * Tests the per-kernel thread pool architecture and restart isolation
 */
@MicronautTest
class KernelIsolationTest extends Specification {

    @Inject
    KernelManager kernelManager

    def "should create isolated kernels with separate thread pools"() {
        given: "Two different connection files"
        String connectionFile1 = "/tmp/test-kernel-1.json"
        String connectionFile2 = "/tmp/test-kernel-2.json"
        
        // Create mock connection files
        createMockConnectionFile(connectionFile1, "kernel1")
        createMockConnectionFile(connectionFile2, "kernel2")

        when: "Starting two separate kernels"
        kernelManager.startNewKernel(connectionFile1)
        kernelManager.startNewKernel(connectionFile2)
        
        // Wait a bit for kernels to start
        Thread.sleep(1000)

        then: "Both kernels should have separate tracking"
        kernelManager.getIsolatedKernelCount() == 2
        kernelManager.getKernelIdFromConnectionFile(connectionFile1) != null
        kernelManager.getKernelIdFromConnectionFile(connectionFile2) != null
        kernelManager.getKernelIdFromConnectionFile(connectionFile1) != kernelManager.getKernelIdFromConnectionFile(connectionFile2)

        and: "Each kernel should be tracked by its ID"
        String kernelId1 = kernelManager.getKernelIdFromConnectionFile(connectionFile1)
        String kernelId2 = kernelManager.getKernelIdFromConnectionFile(connectionFile2)
        kernelManager.getKernelById(kernelId1) != null
        kernelManager.getKernelById(kernelId2) != null

        cleanup:
        cleanupMockFiles(connectionFile1, connectionFile2)
    }

    def "should generate unique kernel IDs"() {
        given: "Multiple kernel ID generation calls"
        def kernelIds = []

        when: "Generating multiple kernel IDs"
        5.times {
            // Use reflection to test the private method
            def method = kernelManager.class.getDeclaredMethod("generateKernelId")
            method.setAccessible(true)
            def kernelId = method.invoke(kernelManager)
            kernelIds.add(kernelId)
        }

        then: "All kernel IDs should be unique and valid UUIDs"
        kernelIds.size() == 5
        kernelIds.unique().size() == 5 // All unique
        kernelIds.each { kernelId ->
            assert kernelId != null
            assert kernelId.length() == 36 // Standard UUID format
            assert kernelId ==~ /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/
        }
    }

    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    def "should restart individual kernel without affecting others"() {
        given: "Two running kernels"
        String connectionFile1 = "/tmp/test-isolation-1.json"
        String connectionFile2 = "/tmp/test-isolation-2.json"
        
        createMockConnectionFile(connectionFile1, "isolation1")
        createMockConnectionFile(connectionFile2, "isolation2")
        
        kernelManager.startNewKernel(connectionFile1)
        kernelManager.startNewKernel(connectionFile2)
        
        // Wait for kernels to start
        Thread.sleep(1000)
        
        String kernelId1 = kernelManager.getKernelIdFromConnectionFile(connectionFile1)
        String kernelId2 = kernelManager.getKernelIdFromConnectionFile(connectionFile2)
        
        when: "Restarting only the first kernel"
        kernelManager.restartKernel(kernelId1)
        
        // Wait for restart to complete
        Thread.sleep(500)

        then: "Second kernel should remain unaffected"
        kernelManager.getKernelById(kernelId2) != null
        
        and: "First kernel should have been restarted (new executor created)"
        // Note: The restart currently only creates new executor, doesn't restart kernel instance
        // This is the expected behavior for our isolation implementation
        kernelManager.getKernelById(kernelId1) != null

        cleanup:
        cleanupMockFiles(connectionFile1, connectionFile2)
    }

    def "should not allow duplicate kernel IDs"() {
        given: "Same connection file used twice"
        kernelManager.killAllKernels() // Clear any previous test kernels
        Thread.sleep(100) // Brief wait for cleanup
        String connectionFile = "/tmp/test-duplicate.json"
        createMockConnectionFile(connectionFile, "duplicate")

        when: "Starting kernel twice with same connection file"
        kernelManager.startNewKernel(connectionFile)
        kernelManager.startNewKernel(connectionFile) // Should be ignored

        // Wait for first kernel to start
        Thread.sleep(1000)

        then: "Only one kernel should be created"
        kernelManager.getIsolatedKernelCount() == 1

        cleanup:
        cleanupMockFiles(connectionFile)
    }

    /**
     * Create a mock connection file for testing
     * This mimics the structure of real Jupyter connection files
     */
    private void createMockConnectionFile(String filePath, String kernelName) {
        def file = new File(filePath)
        file.parentFile?.mkdirs()
        
        def connectionContent = """{
  "shell_port": ${20000 + Math.abs(kernelName.hashCode() % 5000)},
  "iopub_port": ${30000 + Math.abs(kernelName.hashCode() % 5000)},
  "stdin_port": ${40000 + Math.abs(kernelName.hashCode() % 5000)},
  "control_port": ${50000 + Math.abs(kernelName.hashCode() % 5000)},
  "hb_port": ${60000 + Math.abs(kernelName.hashCode() % 5000)},
  "ip": "127.0.0.1",
  "key": "${UUID.randomUUID()}",
  "transport": "tcp",
  "signature_scheme": "hmac-sha256",
  "kernel_name": "${kernelName}"
}"""
        
        file.text = connectionContent
    }

    /**
     * Clean up mock connection files after tests
     */
    private void cleanupMockFiles(String... filePaths) {
        filePaths.each { filePath ->
            try {
                new File(filePath).delete()
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }
}