parameter('jahr', type: 'integer', defaultValue: 2026, description: 'Messjahr')

task('schema', SqlExecutor) {
    database = 'demo'
    scripts = [file('schema.sql')]
}

task('messen', FileSize) {
    input = file('input.txt')
}

task('speichern', SqlExecutor, dependsOn: ['schema', 'messen']) {
    database = 'demo'
    statements = [[sql: 'INSERT INTO messung(jahr, bytes) VALUES (?, ?)',
                   parameters: [parameter('jahr'), result('messen').bytes]]]
}

action('melden', dependsOn: 'speichern') {
    println "SQL-Einträge: ${result('speichern').executionCount}"
}

defaults 'melden'
