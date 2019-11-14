package micronaut.examples.beans

import groovy.util.logging.Slf4j
import io.micronaut.runtime.Micronaut
import groovy.transform.CompileStatic

@Slf4j
@CompileStatic
class Application {

    static void main(String[] args) {
        Micronaut.run(Application)
    }

}
