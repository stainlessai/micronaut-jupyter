package ai.stainless.micronaut.jupyter.kernel;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Bean;

@Factory
public class StandardStreamHandlerFactory {
    @Bean
    StandardStreamHandler standardStreamHandler() {
        return new StandardStreamHandler();
    }
}
