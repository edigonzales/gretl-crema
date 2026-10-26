package probes

class GroovyProbe implements Runnable {
    void run() {
        def twice = { value -> value * 2 }
        def answer = [10, 11].collect(twice).sum()
        if (answer != 42) throw new IllegalStateException('closure result incorrect')
        println "GROOVY_PROBE_OK ${answer}"
    }
}
