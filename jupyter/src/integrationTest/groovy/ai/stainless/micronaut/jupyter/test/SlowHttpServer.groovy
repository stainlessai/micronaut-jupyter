package ai.stainless.micronaut.jupyter.test

import groovy.util.logging.Slf4j
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Slf4j
class SlowHttpServer {
    
    private ServerSocket serverSocket
    private final AtomicBoolean running = new AtomicBoolean(false)
    private final AtomicInteger responseDelayMs = new AtomicInteger(0)
    private final AtomicBoolean neverRespond = new AtomicBoolean(false)
    private def executor = Executors.newCachedThreadPool()
    private int port
    
    /**
     * Start the mock HTTP server on an available port
     */
    void start() {
        try {
            serverSocket = new ServerSocket(0) // Use any available port
            port = serverSocket.getLocalPort()
            running.set(true)
            
            log.info("SlowHttpServer starting on port {}", port)
            
            // Accept connections in background
            CompletableFuture.runAsync({
                acceptConnections()
            }, executor)
            
            // Give the server a moment to start
            Thread.sleep(100)
            log.info("SlowHttpServer started successfully on port {}", port)
            
        } catch (Exception e) {
            log.error("Failed to start SlowHttpServer", e)
            throw new RuntimeException("Failed to start SlowHttpServer", e)
        }
    }
    
    /**
     * Stop the server
     */
    void stop() {
        log.info("Stopping SlowHttpServer on port {}", port)
        running.set(false)
        try {
            if (serverSocket && !serverSocket.isClosed()) {
                serverSocket.close()
            }
            executor.shutdown()
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (Exception e) {
            log.warn("Error stopping SlowHttpServer", e)
        }
        log.info("SlowHttpServer stopped")
    }
    
    /**
     * Set response delay in milliseconds
     */
    void setResponseDelay(int delayMs) {
        responseDelayMs.set(delayMs)
        log.info("Set response delay to {}ms", delayMs)
    }
    
    /**
     * Configure server to never respond (simulate network timeout)
     */
    void setNeverRespond(boolean neverRespond) {
        this.neverRespond.set(neverRespond)
        log.info("Set never respond to {}", neverRespond)
    }
    
    /**
     * Get the server URL
     */
    String getUrl() {
        return "http://localhost:${port}"
    }
    
    /**
     * Get a slow endpoint URL
     */
    String getSlowUrl() {
        return "http://localhost:${port}/slow"
    }
    
    private void acceptConnections() {
        log.debug("Starting to accept connections on port {}", port)
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept()
                log.debug("Accepted connection from {}", clientSocket.remoteSocketAddress)
                
                // Handle each connection in a separate thread
                executor.submit({
                    handleConnection(clientSocket)
                })
                
            } catch (IOException e) {
                if (running.get()) {
                    log.error("Error accepting connection", e)
                } else {
                    log.debug("Server socket closed during shutdown")
                }
                break
            }
        }
        log.debug("Stopped accepting connections")
    }
    
    private void handleConnection(Socket clientSocket) {
        try {
            def input = new BufferedReader(new InputStreamReader(clientSocket.inputStream))
            def output = new PrintWriter(clientSocket.outputStream, true)
            
            // Read the HTTP request (just the first line is enough for our test)
            String requestLine = input.readLine()
            log.debug("Received request: {}", requestLine)
            
            // Skip the rest of the headers
            String line
            while ((line = input.readLine()) != null && !line.isEmpty()) {
                // Skip headers
            }
            
            if (neverRespond.get()) {
                log.debug("Configured to never respond - keeping connection open")
                // Keep the connection open but never respond
                Thread.sleep(60000) // Hold for 1 minute then close
                return
            }
            
            // Apply delay if configured
            int delay = responseDelayMs.get()
            if (delay > 0) {
                log.debug("Applying response delay of {}ms", delay)
                Thread.sleep(delay)
            }
            
            // Send HTTP response
            String responseBody = """{"message": "Hello from SlowHttpServer", "delay": ${delay}, "timestamp": ${System.currentTimeMillis()}}"""
            
            output.println("HTTP/1.1 200 OK")
            output.println("Content-Type: application/json")
            output.println("Content-Length: " + responseBody.length())
            output.println("Connection: close")
            output.println()
            output.println(responseBody)
            output.flush()
            
            log.debug("Sent response after {}ms delay", delay)
            
        } catch (InterruptedException e) {
            log.debug("Connection handling interrupted")
            Thread.currentThread().interrupt()
        } catch (Exception e) {
            log.warn("Error handling connection", e)
        } finally {
            try {
                clientSocket.close()
            } catch (Exception e) {
                log.debug("Error closing client socket", e)
            }
        }
    }
}