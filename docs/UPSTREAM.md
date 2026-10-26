# Herkunft der Codekopie

Quelle: `../gretl-runtime`, Commit `0bb4e5116abf5e7da750d18b9245a194392ee28b`.
Die Kopie ist eigenständig. Build und Ausführung lesen keine Dateien aus dem Quellrepo.
Die MIT-Lizenz und die Copyright-Angabe des Quellprojekts bleiben erhalten.

Übernommen nach `crema-core`:

- Produktionsquellen aus `gretl-api`, `gretl-runner`, `gretl-i18n`, `gretl-groovy`, mit unveränderten Java-Paketnamen.
- API-, Runner- und i18n-Tests aus denselben Modulen.
- `example.ExampleProvider` aus `examples/file-size-extension`.
- `SqlExecutorOperation`, `SqlResult`, `SqlExecutionResult`, `SqlTaskException`, `PreparedStatementSpec` aus `gretl-database-tasks`.

Lokale Anpassungen:

- `GroovyJobLoader` enthält nur `loadCompiled`; der Quellpfad wurde als `SourceJobLoader` zusammen mit `CompilerSettings` nach `crema-compiler` verschoben. Der DSL-Modellaufbau und die verzögerte Taskauswertung bleiben erhalten.
- `JobCompiler` übernimmt die CompilationUnit-/CLASS_GENERATION-Idee des bestehenden Packagers direkt ohne dessen Katalog, Deskriptor, Reflection-Isolation oder Fat-JAR-Paketierung. Hier existiert genau eine festgelegte Runtime-/Groovy-Version.
- Der API-Isolationstest prüft das API-Paket statt des gesamten zusammengelegten Core-Artefakts. Groovy ist in diesem Artefakt absichtlich vorhanden; weitere Fachbibliotheken bleiben ausgeschlossen.
- Die Modul-POMs sind durch den kleinen Drei-Modul-Build ersetzt. Der Maven-Wrapper und seine Prüfsumme stammen aus dem Quellrepo. Der Unix-Wrapper erhält den lokalen GraalVM-Pfad als Default, wenn JAVA_HOME fehlt.

Die Produktionsquellen der API, des sequenziellen Runners, der i18n-Schicht, der fünf SQL-Klassen und des Beispieltasks sind inhaltlich unverändert. Nur der oben beschriebene Loader wird aufgeteilt. Neue Provider-, CLI-, Compiler-, Native-Konfigurations- und Testwerkzeuge gehören zu diesem Repo.

Die Produkt-Spezifikation des Quellrepos wird nicht als vollständiger Lieferumfang dieses Experiments übernommen. Insbesondere werden weder seine gesamte CLI/Distribution noch PostgreSQL-, DuckDB-, INTERLIS- oder GeoTools-Provider kopiert.
