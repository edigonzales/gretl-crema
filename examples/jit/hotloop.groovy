parameter('iterations', type: 'integer', defaultValue: 1000000, description: 'Wiederholungen für die JIT-Probe')

// Deliberately dynamic Groovy, including a closure on the hot path. No CompileStatic.
action('rechnen') {
    def step = { long value -> (value * 1664525L + 1013904223L) & 2147483647L }
    long state = 17L
    int count = parameter('iterations')
    for (int i = 0; i < count; i++) {
        state = step(state)
    }
    println "HOTLOOP_OK ${state}"
}

defaults 'rechnen'
