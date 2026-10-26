parameter('seed', type: 'integer', required: true, description: 'Synthetic runtime training input')

task('initialize', SqlExecutor) {
    database = 'demo'
    scripts = [file('warmup.sql')]
}
task('size', FileSize) { input = file('warmup.txt') }
task('store', SqlExecutor, dependsOn: ['initialize', 'size']) {
    database = 'demo'
    statements = [[sql: 'INSERT INTO runtime_training(seed, bytes) VALUES (?, ?)',
                   parameters: [parameter('seed'), result('size').bytes]]]
}
action('exercise', dependsOn: 'store') {
    def increment = { long value -> value + 3L }
    long value = parameter('seed')
    for (int i = 0; i < 1000; i++) value = increment(value)
    println "RUNTIME_TRAINING_OK ${value}"
}
defaults 'exercise'
