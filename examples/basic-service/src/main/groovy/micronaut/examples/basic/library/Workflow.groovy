package micronaut.examples.basic.library

public class Workflow {

    private class AnonymousOp extends Operation {

        Closure op

        AnonymousOp (Closure op) {
            this.op = op
        }

        public void run () {
            this.status = Status.RUNNING
            try {
                this.outputs = op(this.inputs)
                this.status = Status.SUCCEEDED
            }
            catch (e) {
                this.status = Status.FAILED
            }
        }

    }

    private class WorkflowSpec {

        void add (Closure op) {
            addOp new AnonymousOp(op)
        }

    }

    private List<Operation> ops = []

    public void addOp (Operation op) {
        ops.add(op)
    }

    public void build (@DelegatesTo(strategy=Closure.DELEGATE_ONLY, value=WorkflowSpec) Closure cl) {
        def w = new WorkflowSpec()
        def code = cl.rehydrate(w, this, this)
        code.resolveStrategy = Closure.DELEGATE_ONLY
        code()
    }

    public Object execute (Object inputs) {
        ops.each {
            it.inputs = inputs
            it.run()
            inputs = it.outputs
        }
        return inputs
    }

}
