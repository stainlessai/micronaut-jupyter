package micronaut.examples.basic.library

public abstract class Operation implements Runnable {

    private Status status = Status.IDLE
    private Object inputs
    private Object outputs

    public Status getStatus() {
        return status
    }

    public void setStatus(Status status) {
        this.status = status
    }

    public Object getInputs() {
        return inputs
    }

    public void setInputs(Object inputs) {
        this.inputs = inputs
    }

    public Object getOutputs() {
        return outputs
    }

    public void setOutputs(Object outputs) {
        this.outputs = outputs
    }
}
