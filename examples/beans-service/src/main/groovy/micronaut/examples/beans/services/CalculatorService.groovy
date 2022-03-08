package micronaut.examples.beans.services

import jakarta.inject.Singleton

@Singleton
class CalculatorService {
    
    private List<Map> history = []
    
    public Float sum (Float... args) {
        history.add([
            op: "sum",
            params: args
        ])
        return args.sum()
    }

    public Float difference (Float... args) {
        history.add([
            op: "difference",
            params: args
        ])
        return args[1..-1].inject(args[0]) { result, i -> result - i }
    }

    public Float product (Float... args) {
        history.add([
            op: "product",
            params: args
        ])
        return args.inject(1) { result, i -> result * i }
    }

    public Float quotient (Float... args) {
        history.add([
            op: "quotient",
            params: args
        ])
        return args[1..-1].inject(args[0]) { result, i -> result / i }
    }

    public getHistory () {
        return history
    }

}
