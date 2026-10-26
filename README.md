# GRETL Crema experiment

Ein nativer GRETL-Runner lädt extern kompilierte Groovy-Jobs zur Laufzeit. Dieses Repo untersucht **Crema als Interpreter und mit Ristretto-JIT** mit einem kleinen Taskkatalog: `FileSize` und `SqlExecutor` gegen eingebettetes H2. Die Groovy-DSL und der sequenzielle Runner stammen aus einer eigenständigen [Codekopie](docs/UPSTREAM.md).

## Voraussetzungen

- macOS ARM64 und Xcode Command Line Tools.
- Oracle GraalVM **25.4.4.1.1**, lokal standardmässig unter `$HOME/.sdkman/candidates/java/25.4.4+1-graal`. Alternativ `GRAALVM_HOME` auf genau diese Version setzen.
- Python 3 für native Abnahme und Messung.

Der Maven-Wrapper verwendet bei fehlendem `JAVA_HOME` die genannte GraalVM. Die Skripte ändern weder SDKMAN noch das Standard-JDK. Maven 3.10.0, Groovy 6.0.0 und H2 2.3.232 sind festgelegt; Docker und externe Datenbanken sind nicht nötig.

## Bauen und ausprobieren

```bash
./mvnw verify
./scripts/build-native.sh
mkdir -p out
./bin/gretl-crema-compile examples/demo/job.groovy --output out/job.jar
./bin/gretl-crema run out/job.jar --job-root examples/demo --db out/demo --param jahr=2026
```

Der Demo-Job legt eine Tabelle `MESSUNG` an, leert sie und schreibt Jahresparameter sowie die Grösse von `input.txt` hinein. `--db` ist der Dateipfad ohne `.mv.db`; das Schema wird ausschliesslich in dieser lokalen Testdatenbank verändert.

`--job-root` bestimmt die Basis für `file(...)`. `schema.sql` bleibt extern und wird bei jedem Lauf frisch gelesen. Nur Änderungen an Groovy-Code erfordern ein neues Job-JAR. Das Native Image bleibt bei Jobwechseln dasselbe.

JVM-Vergleich und Quellstart:

```bash
./bin/gretl-crema-jvm run out/job.jar --job-root examples/demo --db out/demo --param jahr=2026
./bin/gretl-crema-source run examples/demo/job.groovy --job-root examples/demo --db out/demo --param jahr=2026
```

Diese beiden JVM-Helfer verwenden das `java` der qualifizierten GraalVM. Der unten beschriebene Vergleich mit der normalen JVM verwendet separat Temurin HotSpot mit den Standard-Compilern C1/C2.

Die Job-JARs enthalten Klassen einschliesslich Closures und die Manifest-Felder `Gretl-Job-Class` und `Gretl-Source-Name`. Sie enthalten weder Runtime-Bibliotheken noch SQL-Dateien. Der Compiler führt keine Taskdefinition oder Task aus. Die gleiche Groovy-Version muss für Compiler und Runner verwendet werden; das Format ist ein lokaler Experimentvertrag, kein kompatibler Ersatz für GRETL-Fat-JARs.

Der Diagnosebefehl `probe <jar> <klasse>` lädt eine Klasse mit öffentlichem parameterlosem Konstruktor, die `Runnable` implementiert. `gretl-crema-compile --plain` kompiliert gewöhnliche Groovy-Klassen für diesen Nachweis. Hilfe und Version sind über `--help` und `--version` verfügbar. Exitcodes: 0 Erfolg, 2 ungültiger CLI-Aufruf, 1 Lade-/Ausführungsfehler, einschliesslich ungültiger deklarierter Jobparameter.

## Prüfungen und Messung

```bash
./scripts/verify-native.sh
./scripts/benchmark.sh
```

Die native Abnahme kompiliert alle Testjobs erst nach dem Native-Build, prüft Java, dynamisches Groovy, GRETL, H2, externe SQL-Änderungen und Fehlerfälle. Ein unabhängiger JDBC-Prozess prüft die Datenbank. Die Binary-Prüfsumme muss vor und nach den Prüfungen gleich sein. Ein fehlgeschlagener nativer Nachweis beendet das Skript mit Fehler; es gibt keinen JVM-Fallback.

Ergebnisse liegen in `crema-runner/target/acceptance/results.json`. Die Messung startet je Variante drei Vorläufe und zehn weitere frische Prozesse, einschliesslich H2-Taskausführung, und schreibt Median/Minimum/Maximum sowie Rohwerte nach `crema-runner/target/benchmark/results.json`. Das ist eine Prozesslaufzeit bei warmen Dateisystem-Caches, keine isolierte JVM-Startzeit und keine Messung eines kalten Betriebssystem-Caches. Native-Bauzeit und Log stehen in `crema-runner/target/native-build.json` und `native-build.log`.

## Ristretto vergleichen

```bash
./mvnw verify
./scripts/build-native.sh
./scripts/build-native.sh --jit
./scripts/verify-native.sh
./scripts/verify-native.sh --jit
./scripts/benchmark-jit.sh
```

`--jit` erzeugt separat `crema-runner/target/gretl-crema-jit` mit `-H:+GraalJITCompileAtRuntime`. Der Interpreter bleibt als `gretl-crema` erhalten. Für eigene Jobaufrufe `./bin/gretl-crema-jit run ...` verwenden. Beide Varianten werden aus denselben JARs mit ansonsten gleicher Buildkonfiguration gebaut; ihre Logs und Buildzeiten bleiben getrennt.

Die JIT-Abnahme schreibt nach `crema-runner/target/acceptance-jit/results.json`. Der Vergleich misst das kurze H2-Demo und einen dynamischen Groovy-Rechenjob mit 100000 Closure-Aufrufen. Je Variante und Job: drei Vorläufe und zehn gemessene frische Prozesse. Die Reihenfolge rotiert pro Messrunde. Zusätzlich läuft das JIT-Binary mit deaktivierter Kompilierung (`-XX:-JITEnableCompilation`), um dessen Startkosten vom Effekt der Kompilierung zu unterscheiden. Die Rechenfixture erlaubt über `--param iterations=...` auch längere Läufe.

Ergebnisse stehen unter `crema-runner/target/benchmark-jit/results.json`. Separate Diagnoseläufe protokollieren Kompilierung und Codeinstallation; sie fliessen nicht in die Zeitmessung ein. Voreingestellte JIT-Schwellen bleiben unverändert. Mehrere frische Prozesse wärmen die Dateisystem-Caches, aber übernehmen keinen JIT-Code aus einem vorherigen Prozess.

## Normale JVM und Pod-pro-Job vergleichen

```bash
./scripts/setup-benchmark-jvm.sh
./scripts/benchmark-jvm.sh
```

Der Setup lädt Temurin HotSpot 25.0.4.1+1 für macOS ARM64 mit fester SHA-256 nach `.tools/`; SDKMAN und Standard-JDK bleiben unverändert. Alternativ `CREMA_JVM_HOME` auf eine passende HotSpot-JVM setzen. Voraussetzung sind die bereits gebauten Runtime-JARs und beide nativen Binaries.

Der Vergleich misst beide Jobs mit vier Varianten: Crema-Interpreter, Ristretto, normale JVM mit Job-JAR und normale JVM mit Groovy-Quellstart. Pro Variante und Job werden drei Vorläufe sowie zehn neue Prozesse ausgeführt; die Reihenfolge rotiert pro Runde. Ergebnisse: `crema-runner/target/benchmark-jvm/results.json`.

**Der JVM-Start ist vollständig enthalten:** Die Uhr läuft vor dem Erzeugen jedes Java-Prozesses bis nach dessen Ende, einschliesslich Klassenladen, JIT-Anlauf, Jobarbeit und beim Quellstart zusätzlich Groovy-Kompilierung. Kein Job nutzt eine bereits laufende JVM oder einen Daemon. Die Vorläufe wärmen Dateisystem-Caches; JIT-Code wird nicht von Prozess zu Prozess übernommen. Eine separate Kompilierung beim Erzeugen eines Job-JARs gehört nicht zur gemessenen Job-JAR-Ausführung.

Diese Messung bildet den Prozessanteil von «JVM starten → Job ausführen → Prozess beenden» ab. OpenShift-Scheduling, Image-Download, Container-/Volume-Vorbereitung und Podabbau werden hier nicht gemessen. Eine Übertragung auf Linux mit euren CPU-/RAM-Limits erfordert eine Messung im Cluster.

Ergebnisse und Schlussfolgerungen: [JVM-Vergleich](docs/JVM-VERGLEICH.md).

## Linux, Speicher, aktuelles Java und CRaC

```bash
./scripts/build-linux.sh
python3 scripts/benchmark-linux.py prepare --kinds jdk27_aot,jdk27_aotserial,jdk27_eager,crac
python3 scripts/benchmark-linux.py prepare --kinds jdk28_aot --preparation-memory 2g
python3 scripts/benchmark-linux.py smoke
python3 scripts/validate-linux-databases.py
python3 scripts/benchmark-linux.py measure
./scripts/verify-linux-jvms.sh
```

Die Linux-ARM64-Reihe verwendet für jeden Joblauf einen neuen Docker-Container mit **2 CPUs, 1 GiB RAM, ohne zusätzlichen Swap und 32–512 MiB Heap**. Native Binaries werden eigens unter Linux gebaut; die macOS-Binaries bleiben erhalten. Alle Messvarianten verwenden dasselbe vorbereitete Image, feste Runtime-Archive mit Prüfsummen und dieselben Jobs. Die Images sind vor den Messungen lokal vorhanden.

Erfasst werden Prozessstart bis Prozessende, Docker-Startaufruf bis Containerende, Containererstellung bis Containerende, Peak-RSS, cgroup-`memory.peak` und CPU-Zeit. RSS wird über Linux-Ressourcenstatistik ermittelt, nicht durch gelegentliche `docker stats`-Stichproben. Die Speicherkennzahlen umfassen unterschiedliche Dinge: RSS enthält residente Prozessmappings, die cgroup-Zahl auch den Container-Beobachter und zugerechneten Dateicache.

Neben Java 25 werden die aktuelle stabile Oracle-JVM 27.0.1.0.3, deren AOT-/Profilcaches und zwei zusätzliche Einstellungen untersucht: Serial GC und experimentelles `AOTCompileEagerly`. Java 28 EA Build 21 wird separat mit der neuen AOT-Maschinencode-Kompilierung geprüft. Kompakte Objekt-Header sind im verwendeten Java 27 bereits standardmässig eingeschaltet. AOT-Läufe verwenden `AOTMode=required`, damit ein fehlender Cache auffällt. Die einmalige Cache-Erzeugung für Java 28 verwendet 2 GiB Build-RAM; alle gemessenen Läufe bleiben bei 1 GiB.

CRaC verwendet Azul Zulu 25 mit der echten Linux-Warp-Engine. Der Benchmarkadapter führt drei isolierte Trainingsjobs aus, erstellt dann einen Checkpoint **vor** dem gemessenen Job und führt diesen erst nach dem Restore vollständig aus. Kalte Starts derselben CRaC-JVM werden als Kontrolle mitgemessen. Checkpoints und AOT-Caches werden ausserhalb der Laufzeitmessung erzeugt; Vorbereitungszeit und Artefaktgrösse werden separat protokolliert. Es sind keine simulierten Checkpoints.

Runtime-Downloads liegen unter `.tools/linux/`; Linux-Binaries, AOT-Caches und Checkpoints in den Docker-Volumes `gretl-crema-linux-artifacts`, `gretl-crema-linux-aot` und `gretl-crema-linux-checkpoints`. Messungen und Rohdaten liegen unter `crema-runner/target/linux/`. Der Linux-Build benötigt mehr Ressourcen als die Messläufe: 4 CPUs und 8 GiB für Native Image.

Die Reihe misst Docker unter Linux ARM64 in Docker Desktop. Sie bildet keine OpenShift-Scheduling- oder Image-Pull-Latenz ab und ist kein kalter Node-/Dateicache-Test. Das gemeinsame Image enthält absichtlich alle Test-Runtimes und ist kein optimiertes Produktionsimage.

Auswertung: [Linux-Prüfbericht](docs/LINUX-VERGLEICH.md).

Ein generischer CRaC-Checkpoint für wechselnde Job-JARs:

```bash
./scripts/build-generic-crac.sh
python3 scripts/benchmark-linux.py smoke --suite generic
python3 scripts/validate-linux-databases.py --suite generic
python3 scripts/benchmark-linux.py measure --suite generic
python3 scripts/collect-linux-metadata.py --suite generic
python3 scripts/archive-linux.py --suite generic
```

Der Bootstrap trainiert einen festen synthetischen GRETL-Job; die Demo-Jobs sind dabei ausgeblendet und werden erst nach dem Checkpoint kompiliert. Derselbe schreibgeschützte Checkpoint übernimmt beim Restore den Jobpfad und alle CLI-Parameter. Die zusätzliche Reihe misst acht Varianten erneut mit denselben Limits und frischen Containern. Ergebnisse: generischer Restore 64 ms für H2 und 75 ms für die Groovy-Rechenprobe, gegenüber 68/58 ms mit jobbezogenen Checkpoints. Alle Werte enthalten Restore und vollständige Jobarbeit; die gemeinsame Vorbereitung liegt separat bei 0,55 s. Details und Speicherwerte im [Nachtrag](docs/LINUX-VERGLEICH.md#nachtrag-generischer-crac-runner).

## Grenzen

Crema und die Groovy-Native-Unterstützung sind experimentell. Das Binary enthält einen festgelegten Taskkatalog; nur die Jobs werden nachgeladen. Parallelität, zusätzliche Task-JARs, Layers, PostgreSQL, DuckDB, INTERLIS und GeoTools sind nicht Gegenstand dieses Versuchs.

Der native Start verwendet weiterhin `lib/modules` der qualifizierten GraalVM über `java.home`. Diese Datei stellt Klassen bereit; es wird kein HotSpot-Prozess gestartet. Das Ergebnis ist deshalb kein eigenständig verteilbares Einzelbinary. Die beim Build erzeugten `.dylib`-Dateien müssen neben dem Binary bleiben.

Native-Metadaten und `Preserve` halten die benötigte DSL-/SPI-Fläche offen. Zusätzlich werden `java.lang`, `java.lang.invoke`, `java.io` und `java.util` erhalten: Die Nachweise benötigen insbesondere `System.out`, Method-Handle-Verknüpfung und dynamische Standardbibliotheksaufrufe. Das vergrössert das Binary und garantiert keine beliebigen Groovy-Programme. Details: [Crema in GraalVM 25.4.4.1.1](https://github.com/oracle/graal/blob/vm-25.4.4.1.1/substratevm/docs/runtime-class-loading.md).

Die ursprünglichen Interpreter-Ergebnisse stehen im [ersten Prüfbericht](docs/PRUEFBERICHT.md); den aktuellen Vergleich mit Ristretto dokumentiert der [JIT-Prüfbericht](docs/RISTRETTO.md).
