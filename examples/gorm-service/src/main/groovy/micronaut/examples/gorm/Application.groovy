package micronaut.examples.gorm

import groovy.transform.CompileStatic
import io.micronaut.runtime.Micronaut

@CompileStatic
class Application {
    static void main(String[] args) {
        Micronaut.build(args)
                .packages("micronaut.example.gorm.domains")
                .mainClass(Application.class)
                .start()
    }

}
