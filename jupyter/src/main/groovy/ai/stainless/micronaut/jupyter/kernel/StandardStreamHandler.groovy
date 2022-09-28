package ai.stainless.micronaut.jupyter.kernel

import com.twosigma.beakerx.jvm.threads.BeakerInputHandler
import com.twosigma.beakerx.jvm.threads.BeakerOutputHandler
import com.twosigma.beakerx.widget.OutputManager
import groovy.util.logging.Slf4j

import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/*
 * Based on BeakerX's BeakerStdInOutErrHandler class:
 * https://github.com/twosigma/beakerx/blob/master/kernel/base/src/main/java/com/twosigma/beakerx/jvm/threads/BeakerStdInOutErrHandler.java
 */
@Slf4j
public class StandardStreamHandler {

    private ClassContextSecurityManager classContextSecurityManager = new ClassContextSecurityManager()
    private loggingSearchDepth = 50
    private Pattern loggingSearchPattern = Pattern.compile(
            [
                    "org.apache.log4j", "org.slf4j", "org.jboss.logging",
                    "ch.qos.logback"
            ]
                    .collect { "(${it.replaceAll('\\.', '\\.')})" }
                    .join("|")
    )

    private Map<ThreadGroup, BeakerOutputHandlers> handlers = [:]
    private PrintStream orig_out
    private PrintStream orig_err
    private InputStream orig_in

    Boolean redirectLogOutput

    public void init () {
        orig_out = System.out
        orig_err = System.err
        orig_in = System.in
        try {
            System.setOut(
                    new PrintStream(
                            new ProxyOutputStream(handler: this, isOut: true),
                            false,
                            StandardCharsets.UTF_8.name()
                    )
            )
            System.setErr(
                    new PrintStream(
                            new ProxyOutputStream(handler: this, isOut: false),
                            false,
                            StandardCharsets.UTF_8.name()
                    )
            )
            System.setIn(
                    new ProxyInputStream(handler: this)
            )
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    // this will be called when the KernelManager is refreshed.
    // NOTE will destroy the streams when it happens
    public void restore() {
        log.trace("restore")
        System.setOut(orig_out)
        System.setErr(orig_err)
        System.setIn(orig_in)
    }

    synchronized public void setOutputHandlers(
            BeakerOutputHandler out,
            BeakerOutputHandler err,
            BeakerInputHandler stdin
    ) {
        log.trace("setOutputHandlers")
        removeHandlersWithAllNoAliveThreads()
        //get current thread group
        ThreadGroup threadGroup = Thread.currentThread().getThreadGroup()
        //store handlers for current thread group
        handlers.put(threadGroup, new BeakerOutputHandlers(
                out_handler: out,
                err_handler: err,
                in_handler:stdin
        ))
    }

    synchronized public void clearOutputHandlers () {
        log.trace("clearOutputHandlers")
        removeHandlersWithAllNoAliveThreads()
    }

    /*
     * In order to redirect logging output to a different stream, we need to
     * determine whether or not the output came from a logging platform.
     *
     * Currently supported logging platforms:
     * - Log4j
     * - Slf4j
     * - Logback
     *
     * The current method for doing this is to inspect the call stack. Have a
     * better way? Comment at: https://github.com/stainlessai/micronaut-jupyter/issues/6
     * The chosen for method for inspecting the call stack is to use a security
     * manager, because this answer indicates it is fastest:
     * https://stackoverflow.com/a/2924426/3727785
     */
    private Boolean isLoggingCall () {
        // get class context
        Class[] classContext = classContextSecurityManager.getCallerClassContext()
        Long classContextSize = classContext.size()
        String classContextCombined = ""
        // do a for loop to try and boost performance (not profiled)
        for (int i=1; i<classContextSize && i<loggingSearchDepth; i++) {
            classContextCombined += classContext[i].name
        }
        // it is theorized that regexing the combined class array will be
        // faster (not profiled)
        return (classContextCombined =~ loggingSearchPattern).size() > 0
    }

    synchronized private void removeHandlersWithAllNoAliveThreads() {
        handlers.findAll { it.key.activeCount() == 0 }.each {
            it.value.destroy()
            handlers.remove(it.key)
            log.trace("destroyed/removed handler "+it.key)
        }
    }

    synchronized public void writeStream(String text, Boolean isOut) throws IOException {
        // get stream info (out or err)
        log.trace("writeStream '"+text+"', isOut="+isOut);
        Boolean sendStream
        String handlerName
        PrintStream systemStream
        if (isOut) {
            sendStream = OutputManager.sendStdout(text);
            handlerName = "out_handler"
            systemStream = orig_out
        }
        else {
            sendStream = OutputManager.sendStderr(text);
            handlerName = "err_handler"
            systemStream = orig_err
        }
        // if we the output manager accepted this text
        if (sendStream) {
            // then don't write this string
            return
        }   // else, go ahead and write to stream

        if (!sendStream) {
            BeakerOutputHandlers hrs = handlers.get(Thread.currentThread().getThreadGroup())
            // FIXME "hrs" can be null, trying to debug why, but that means the streams will be messed up
            log.trace("isLoggingCall="+isLoggingCall())
            log.trace("hrs="+hrs)
            // if we have registered handlers, and this is NOT a logging call that we are to redirect
            if (hrs != null && hrs."$handlerName" != null && (!redirectLogOutput || !isLoggingCall())) {
                // write to our handlers
                log.trace("writing to handler: $handlerName")
                hrs."$handlerName".write(text)
            } else {
                // write to this system stream
                systemStream.write(text)
            }
        }
    }

    synchronized public int readStdin() {
        BeakerOutputHandlers hrs = handlers.get(Thread.currentThread().getThreadGroup())
        if (hrs != null && hrs.in_handler != null) {
            return hrs.in_handler.read()
        } else {
            return orig_in.read()
        }
    }


    private class ProxyInputStream extends InputStream {

        StandardStreamHandler handler

        @Override
        public int read() throws IOException {
            return handler.readStdin()
        }
    }

    private class ProxyOutputStream extends OutputStream {

        private boolean isOut
        StandardStreamHandler handler

        @Override
        public void write(int b) throws IOException {
            log.trace("write: "+Byte.toString(b))
            byte[] ba = new byte[1]
            ba[0] = (byte) b
            String s = new String(ba, StandardCharsets.UTF_8)
            writeStream(s)
        }

        @Override
        public void write(byte[] b) throws IOException {
            log.trace("write: "+new String(b))
            String s = new String(b, StandardCharsets.UTF_8)
            writeStream(s)
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            log.trace("write: "+new String(b)+","+off+","+len)
            String s = new String(b, off, len, StandardCharsets.UTF_8)
            writeStream(s)
        }

        private void writeStream(String s) throws IOException {
            log.trace("writeStream: "+s)
            handler.writeStream(s, isOut)
        }
    }

    public static class BeakerOutputHandlers {

        BeakerOutputHandler out_handler
        BeakerOutputHandler err_handler
        BeakerInputHandler in_handler

        public void destroy () {
            out_handler = null
            err_handler = null
            in_handler = null
        }

    }

    private class ClassContextSecurityManager extends SecurityManager {
        public Class[] getCallerClassContext () {
            return getClassContext()
        }
    }

}
