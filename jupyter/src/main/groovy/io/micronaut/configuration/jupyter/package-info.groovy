/**
 * Jupyter integration with Micronaut.
 *
 * @author Joshua Carter
 * @see <a href="https://jupyter.org/">Jupyter</a>
 */
@Configuration
@Requires(property = "jupyter.enabled", notEquals = "false")
package io.micronaut.configuration.jupyter

import io.micronaut.context.annotation.Configuration
import io.micronaut.context.annotation.Requires
