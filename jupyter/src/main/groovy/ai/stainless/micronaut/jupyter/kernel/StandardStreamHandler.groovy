package ai.stainless.micronaut.jupyter.kernel;

import com.twosigma.beakerx.jvm.threads.BeakerInputHandler;
import com.twosigma.beakerx.jvm.threads.BeakerOutputHandler;
import com.twosigma.beakerx.widget.OutputManager;
import groovy.util.logging.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handles standard stream redirection and management for Jupyter notebooks.
 * Based on BeakerX's BeakerStdInOutErrHandler class.
 */
@Slf4j
public class StandardStreamHandler {
    private static final int MAX_STACK_DEPTH = 50;

    private static final Pattern LOGGING_SEARCH_PATTERN = Pattern.compile(
            Stream.of(
                    "org.apache.log4j", "org.slf4j", "org.jboss.logging",
                    "ch.qos.logback"
            )
                    .map(pkg -> "(" + pkg.replaceAll("\\.", "\\\\.") + ")")
                    .collect(Collectors.joining("|"))
    );

    // Thread-safe map for handlers
    private final Map<ThreadGroup, BeakerOutputHandlers> handlers = new ConcurrentHashMap<>();

    // Original stream references
    private PrintStream orig_out;
    private PrintStream orig_err;
    private InputStream orig_in;

    // Configuration property
    private Boolean redirectLogOutput = true;

    /**
     * Get the redirectLogOutput setting
     * @return true if log output should be redirected, false otherwise
     */
    public Boolean getRedirectLogOutput() {
        return redirectLogOutput;
    }

    /**
     * Set the redirectLogOutput setting
     * @param redirectLogOutput true to redirect log output, false otherwise
     */
    public void setRedirectLogOutput(Boolean redirectLogOutput) {
        this.redirectLogOutput = redirectLogOutput;
    }

    /**
     * Initialize the stream handler by capturing and redirecting system streams
     */
    public void init() {
        log.debug("Initializing StandardStreamHandler");
        orig_out = System.out;
        orig_err = System.err;
        orig_in = System.in;

        try {
            System.setOut(
                    new PrintStream(
                            new ProxyOutputStream(handler: this, isOut: true),
                            false,
                            StandardCharsets.UTF_8.name()
                    )
            );
            System.setErr(
                    new PrintStream(
                            new ProxyOutputStream(handler: this, isOut: false),
                            false,
                            StandardCharsets.UTF_8.name()
                    )
            );
            System.setIn(
                    new ProxyInputStream(handler: this)
            );
            log.debug("System streams successfully redirected");
        } catch (UnsupportedEncodingException e) {
            log.error("Failed to initialize stream handler", e);
            throw new RuntimeException("Failed to initialize stream handler", e);
        }
    }

    /**
     * Restore original system streams
     */
    public void restore() {
        log.debug("Restoring original system streams");
        System.setOut(orig_out);
        System.setErr(orig_err);
        System.setIn(orig_in);
    }

    /**
     * Set output handlers for the current thread group
     *
     * @param out Output handler for stdout
     * @param err Output handler for stderr
     * @param stdin Input handler for stdin
     */
    public void setOutputHandlers(BeakerOutputHandler out, BeakerOutputHandler err, BeakerInputHandler stdin) {
        log.debug("Setting output handlers for thread group: {}", Thread.currentThread().getThreadGroup().getName());

        // Remove handlers for thread groups with no active threads
        removeHandlersWithAllNoAliveThreads();

        // Store handlers for current thread group
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup();
        handlers.put(threadGroup, new BeakerOutputHandlers(
                out_handler: out,
                err_handler: err,
                in_handler: stdin
        ));
    }

    /**
     * Clear all output handlers
     */
    public void clearOutputHandlers() {
        log.debug("Clearing output handlers");
        removeHandlersWithAllNoAliveThreads();
    }

    /**
     * Check if the current call originated from a logging framework
     *
     * @return true if call came from logging framework, false otherwise
     */
    private Boolean isLoggingCall() {
        // Get call stack
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();

        // Build string of class names for pattern matching
        StringBuilder classNames = new StringBuilder();
        int limit = Math.min(stackTrace.length, MAX_STACK_DEPTH);

        for (int i = 1; i < limit; i++) {
            classNames.append(stackTrace[i].getClassName());
        }

        // Check if any logging framework classes are in the call stack
        return (classNames.toString() =~ LOGGING_SEARCH_PATTERN).size() > 0;
    }

    /**
     * Remove handlers for thread groups that have no active threads
     */
    private void removeHandlersWithAllNoAliveThreads() {
        handlers.entrySet().removeIf(entry -> {
            ThreadGroup group = entry.getKey();
            if (group.activeCount() == 0) {
                entry.getValue().destroy();
                log.trace("Removed handler for inactive thread group: {}", group.getName());
                return true;
            }
            return false;
        });
    }

    /**
     * Write text to the appropriate stream
     *
     * @param text Text to write
     * @param isOut true for stdout, false for stderr
     */
    public void writeStream(String text, Boolean isOut) throws IOException {
        if (text == null || text.isEmpty()) {
            return;
        }

        log.trace("writeStream: '{}', isOut={}", text, isOut);

        // Determine stream properties
        Boolean sendStream;
        String handlerName;
        PrintStream systemStream;

        if (isOut) {
            sendStream = OutputManager.sendStdout(text);
            handlerName = "out_handler";
            systemStream = orig_out;
        } else {
            sendStream = OutputManager.sendStderr(text);
            handlerName = "err_handler";
            systemStream = orig_err;
        }

        // If OutputManager accepted the text, we're done
        if (sendStream) {
            return;
        }

        // Get handlers for current thread group
        ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
        BeakerOutputHandlers hrs = handlers.get(currentThreadGroup);

        // Determine if this is a logging call that should be redirected
        boolean isLoggingCallToRedirect = redirectLogOutput && isLoggingCall();

        if (hrs != null && hrs."$handlerName" != null && !isLoggingCallToRedirect) {
            // Write to custom handler
            try {
                hrs."$handlerName".write(text);
            } catch (Exception e) {
                log.warn("Error writing to handler, falling back to system stream", e);
                systemStream.print(text);
            }
        } else {
            // Write to system stream
            systemStream.print(text);
        }
    }

    /**
     * Read from stdin
     *
     * @return The character read, or -1 if the end of the stream has been reached
     */
    public int readStdin() {
        ThreadGroup currentThreadGroup = Thread.currentThread().getThreadGroup();
        BeakerOutputHandlers hrs = handlers.get(currentThreadGroup);

        if (hrs != null && hrs.in_handler != null) {
            try {
                return hrs.in_handler.read();
            } catch (Exception e) {
                log.warn("Error reading from input handler, falling back to system input", e);
                return orig_in.read();
            }
        } else {
            return orig_in.read();
        }
    }

    /**
     * InputStream implementation that delegates to StandardStreamHandler
     */
    private class ProxyInputStream extends InputStream {
        StandardStreamHandler handler;

        @Override
        public int read() throws IOException {
            return handler.readStdin();
        }
    }

    /**
     * OutputStream implementation that delegates to StandardStreamHandler
     */
    private class ProxyOutputStream extends OutputStream {
        private boolean isOut;
        StandardStreamHandler handler;

        @Override
        public void write(int b) throws IOException {
            byte[] ba = new byte[1];
            ba[0] = (byte) b;
            String s = new String(ba, StandardCharsets.UTF_8);
            writeStream(s);
        }

        @Override
        public void write(byte[] b) throws IOException {
            if (b == null || b.length == 0) {
                return;
            }
            String s = new String(b, StandardCharsets.UTF_8);
            writeStream(s);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (b == null || len <= 0 || off < 0 || off >= b.length) {
                return;
            }
            String s = new String(b, off, len, StandardCharsets.UTF_8);
            writeStream(s);
        }

        private void writeStream(String s) throws IOException {
            handler.writeStream(s, isOut);
        }
    }

    /**
     * Container for output and input handlers
     */
    public static class BeakerOutputHandlers {
        BeakerOutputHandler out_handler;
        BeakerOutputHandler err_handler;
        BeakerInputHandler in_handler;

        public void destroy() {
            out_handler = null;
            err_handler = null;
            in_handler = null;
        }
    }
}