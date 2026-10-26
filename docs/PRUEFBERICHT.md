# Prüfbericht: GRETL mit Crema und H2

Dieser Bericht archiviert den ersten Interpreterversuch samt damaligen Binary-Prüfsummen und Messwerten. Den neueren Vergleich beider nativen Varianten dokumentiert [RISTRETTO.md](RISTRETTO.md).

Prüfdatum: 26. Oktober 2026. Ergebnis: **alle zugesagten Nachweise erfolgreich**, einschliesslich echter dynamischer Groovy-Closures und SQL im nativen Prozess.

## Umgebung und Build

- macOS 27.0.1, ARM64, Apple M5, 24 GiB RAM.
- Oracle GraalVM 25.4.4.1.1+1.1, JDK 25.0.4.1.1+1-LTS; Maven-Wrapper 3.10.0.
- Groovy 6.0.0, H2 2.3.232; keine weitere JDBC-Engine.
- Native-Build mit RuntimeClassLoading und AllowJRTFileSystem; GraalJITCompileAtRuntime ausdrücklich deaktiviert.
- Erfolgreicher abschliessender Build: 150 Sekunden, Exitcode 0.
- Binary: 187,181,640 Bytes (178.51 MiB). Zusätzlich 11 erzeugte dylibs mit zusammen 5.07 MiB neben dem Binary; die installierte GraalVM wird weiterhin für `lib/modules` benötigt.

Die Jobklassen und ihre Closures waren nicht im Native-Image-Classpath. Sämtliche Abnahmejobs wurden erst nach dem Native-Build kompiliert. Die SHA-256 des Binary blieb während der kompletten Abnahme unverändert:

`77ad7a5c92bc7bdff24f843a7c47e98f3636d5eb82e51fdc768e2cee4929aaed`

## Ausgeführte Prüfungen

`./mvnw verify`: **204 Tests**, 0 Fehler, 0 Fehlschläge, 0 übersprungene Tests. Davon 200 übernommene Kernprüfungen und vier Integrationstests für Compiler, Loader, Parameter, echte H2-Daten, externe SQL-Änderungen und Fehlerfälle.

`./scripts/build-native.sh`: erfolgreich. `./scripts/verify-native.sh`: **12 von 12 Prüfungen bestanden**, Exitcode 0:

- java_class_loading: bestanden
- groovy_class_and_closure: bestanden
- missing_entry_class: bestanden
- invalid_cli: bestanden
- gretl_parameter_and_predecessor: bestanden
- gretl_sql_and_independent_jdbc: bestanden
- jvm_native_equivalence: bestanden
- external_sql_without_recompile: bestanden
- second_job_compiled_after_native_build: bestanden
- invalid_parameter: bestanden
- missing_sql_file: bestanden
- invalid_sql: bestanden

Der JDBC-Prüfprozess liest die gespeicherten Jahres- und Grössenwerte unabhängig vom Runner. Der zweite Job wurde mit einem anderen Klassennamen neu kompiliert und liefert den geänderten Jahreswert 2031. Eine Änderung an der externen SQL-Datei erzeugt einen zusätzlichen, ebenfalls per JDBC geprüften Datensatz, während Job-JAR und Binary unverändert bleiben. JVM- und Native-Ausführung liefern dieselben fachlichen Ergebnisse und Taskausgänge.

Die nicht gefundenen Klassen, ungültigen Parameter, fehlenden SQL-Dateien und fehlerhaften SQL-Anweisungen sind erwartete Negativtests mit geprüften Fehlercodes beziehungsweise Fehlerursachen. Sie wurden nicht als übersprungene Tests behandelt.

## Messungen

`./scripts/benchmark.sh`: pro Variante drei Vorläufe und zehn weitere frische Prozesse. Gemessen wird direkt vom Prozessstart bis zum Prozessende, **einschliesslich Jobdefinition, Dateimessung, H2-Datenbanköffnung und SQL-Ausführung**. Die Java-Prozesse beziehungsweise das native Binary werden direkt gestartet; Shell- und Toolchain-Erkennung sind nicht Teil der Messwerte. Die Native-Bauarbeit war vor Beginn der Messung abgeschlossen.

| Variante | Median | Minimum–Maximum |
|---|---:|---:|
| Groovy-Quellstart auf JVM | 691.8 ms | 652.2–1043.5 ms |
| Job-JAR auf JVM | 464.6 ms | 411.4–614.2 ms |
| Job-JAR mit Crema-Interpreter | 118.1 ms | 74.2–132.7 ms |

Das sind lokale Vergleichswerte bei warmen Dateisystem-Caches. Sie isolieren weder den JVM-Start noch die erste Task und messen keinen kalten Betriebssystem-Cache. Sie erlauben keine Aussage über lange ETL-Jobs, andere Groovy-Programme oder andere Plattformen. Es wurde keine Mindestbeschleunigung als Abnahmekriterium verwendet.

Rohwerte und maschinenlesbare Nachweise: [Messung](results/benchmark.json), [native Abnahme](results/acceptance.json), [Native-Build](results/native-build.json). Vollständige aktuelle Prozessausgaben liegen unter `crema-runner/target/acceptance/results.json` und `native-build.log`.

## Konfiguration und Grenzen

Die ersten Ladeversuche zeigten fehlende Zugriffe auf `System.out` sowie nicht erhaltene Method-Handle-Verknüpfungen. Die erfolgreiche Konfiguration erhält deshalb neben GRETL-DSL/SPI die JDK-Pakete `java.lang`, `java.lang.invoke`, `java.io` und `java.util`. Das ist bewusst eine Kompatibilitätsprobe; Binary-Grösse und Metadaten wurden nicht optimiert. Weder Beispieljobs noch deren Klassen wurden dafür in das Image aufgenommen.

Der native Start ruft keinen HotSpot-Prozess auf, auch nicht zur Versionsprüfung. Er verwendet aber die qualifizierte JDK-Installation für zusätzliche Klassen. Die neben dem Binary erzeugten dylibs müssen erhalten bleiben. Ein eigenständig verteilbares Einzelbinary, beliebige zusätzliche Taskbibliotheken, paralleles Klassenladen, Ristretto, Layers und weitere Datenbanken sind nicht qualifiziert.

Der Katalog enthält ausschliesslich FileSize und SqlExecutor. SQL wurde gegen H2 geprüft, nicht gegen PostgreSQL. Ein Groovy-Quellcompiler läuft nur im JVM-Werkzeug, nie im nativen Jobpfad.

## Quellrepo

Dieser Auftrag hat keine Dateien in `../gretl-runtime` verändert. 119 kopierte Produktionsdateien wurden abschliessend byteweise gegen den festgelegten Git-Commit `0bb4e5116abf5e7da750d18b9245a194392ee28b` geprüft und stimmen überein. Die bewusste Loader-Aufteilung und alle neuen Bestandteile sind in [UPSTREAM.md](UPSTREAM.md) dokumentiert.

Das Nachbarrepo war beim Start sauber. Bei der Abschlussprüfung waren dort inzwischen parallele Änderungen an CLI-Dokumentationscode, Sprachressourcen und Referenzdokumentation sichtbar. Diese wurden weder übernommen noch verändert oder zurückgesetzt. Die Commit-Prüfung stellt sicher, dass der Prototyp weiterhin den dokumentierten Quellstand verwendet.
