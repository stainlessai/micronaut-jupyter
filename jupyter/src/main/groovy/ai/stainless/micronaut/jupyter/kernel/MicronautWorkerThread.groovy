package ai.stainless.micronaut.jupyter.kernel

/*
 * Implementation of the GroovyWorkerThread class from BeakerX.
 * BeakerX license pasted below.
 */

/*
 *  Copyright 2017 TWO SIGMA OPEN SOURCE, LLC
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

import com.twosigma.beakerx.TryResult
import com.twosigma.beakerx.evaluator.JobDescriptor
import com.twosigma.beakerx.groovy.evaluator.GroovyCodeRunner
import com.twosigma.beakerx.groovy.evaluator.GroovyNotFoundException
import groovy.util.logging.Slf4j
import java.util.concurrent.Callable

@Slf4j
class MicronautWorkerThread implements Callable<TryResult> {

    private JobDescriptor j
    protected MicronautEvaluator evaluator

    MicronautWorkerThread(MicronautEvaluator evaluator, JobDescriptor j) {
        this.evaluator = evaluator
        this.j = j
    }

    private def executeCode () {
        return (new MicronautCodeRunner(evaluator, j.codeToBeExecuted, j.outputObject)).call()
    }

    @Override
    public TryResult call() {
        TryResult r
        try {
            j.outputObject.started()

            // execute
            r = evaluator.executeTask({
                //get HibernateDatastore class
                Class HibernateDatastore
                try {
                    HibernateDatastore = "org.grails.orm.hibernate.HibernateDatastore" as Class
                }
                catch (Throwable e) { }
                if (!HibernateDatastore) {
                    log.debug ("Class org.grails.orm.hibernate.HibernateDatastore not found in classpath")
                    return executeCode()
                }
                log.debug "Found class org.grails.orm.hibernate.HibernateDatastore"
                //search for HibernateDatastore bean
                if (!evaluator.kernel.applicationContext.containsBean(HibernateDatastore)) {
                    log.debug("No HibernateDatastore bean found")
                    return executeCode()
                }
                log.debug("Found HibernateDatastore bean")
                return evaluator.kernel.applicationContext.getBean(HibernateDatastore).withNewSession { session ->
                    def result = executeCode()

                    // attempt to flush session
                    try {
                        session.flush()
                    }
                    catch (Throwable e) {
                        log.debug "Error while flushing session: $e"
                    }

                    return result
                }
            } as Callable, j.getExecutionOptions())
        } catch (Throwable e) {
            if (e instanceof GroovyNotFoundException) {
                logger.warn(e.getLocalizedMessage())
                r = TryResult.createError(e.getLocalizedMessage())
            } else {
                e.printStackTrace()
                r = TryResult.createError(e.getLocalizedMessage())
            }
        }
        return r
    }
}
