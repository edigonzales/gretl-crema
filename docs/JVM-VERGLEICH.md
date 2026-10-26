# Normale JVM versus beide nativen Crema-Varianten

Prüfdatum: 26. Oktober 2026. **Der JVM-Start ist in sämtlichen hier genannten JVM-Zeiten vollständig enthalten.** Für das kurze H2-Demo gewinnt der native Interpreter; für die Groovy-Rechenprobe gewinnt die normale JVM, selbst einschliesslich ihres Starts.

## Messaufbau

- macOS 27.0.1 ARM64, Apple M5, 24 GiB RAM; lokale Prozesse, kein OpenShift-Cluster.
- Normale JVM: **Eclipse Temurin HotSpot 25.0.4.1+1**, Standard-Compiler C1/C2 und unveränderte JVM-Voreinstellungen. Kein `-Xint`, keine angepassten Heap-/Tiering-/Compileroptionen. `UseCompiler` und `TieredCompilation` stehen auf `true`.
- Die JVM wurde aus dem [offiziellen Temurin-Release](https://github.com/adoptium/temurin25-binaries/releases/tag/jdk-25.0.4.1%2B1) lokal nach `.tools/` geladen; SHA-256 des Archivs: `61979887f7506a24a57439ff99adb8b3a7fc89977d9cfe3b8984f58a981b7b9d`. SDKMAN und das Standard-JDK wurden nicht verändert.
- Native Binaries: unverändert aus dem vorherigen Versuch, Oracle GraalVM 25.4.4.1.1 mit JDK 25.0.4.1.1. Die unterschiedlichen Distributionen und Patchbezeichnungen sind dokumentiert; es handelt sich nicht um denselben HotSpot-Build.
- Alle vier Varianten wurden in **einer neuen Messrunde** für beide Jobs gemessen. Keine Mischung alter Native-Zahlen mit neuen JVM-Zahlen. Je Kombination drei Vorläufe und zehn gemessene frische Prozesse, rotierende Reihenfolge je Runde, zusammen 80 gemessene Prozesse.
- Gleiche Runtime-/Groovy-/H2-JARs und identische fachliche Jobs. Native und JVM-Bytecode laufen mit demselben Job-JAR; der Quellstart kompiliert die zugehörige Groovy-Datei im gestarteten Prozess. Bibliotheks- und Binary-Prüfsummen sind in den Rohdaten festgehalten.
- Keine Native-Builds während der Messung. Ausgabe und korrekter Jobstatus werden bei jedem Prozess geprüft; die Rechenprobe liefert immer `326690737`. Die finalen H2-Daten wurden unabhängig per JDBC auf Jahr 2026 und die tatsächliche Dateigrösse von 17 Bytes geprüft.

**Die früheren JVM-Zahlen** im ersten Prüfbericht verwendeten das `java` der Oracle GraalVM. Diese neue Messreihe verwendet bewusst eine normale Temurin-HotSpot-JVM. Die älteren Zahlen bleiben historische Daten und sind nicht als Temurin-Messungen bezeichnet.

## Gesamtprozesslaufzeiten

| Variante | H2-Demo, Median | Groovy-Rechenprobe, Median |
|---|---:|---:|
| Crema-Interpreter | 100.9 ms | 2.463 s |
| Crema mit Ristretto-JIT | 161.2 ms | 1.544 s |
| Temurin HotSpot, vorkompiliertes Job-JAR | 604.2 ms | 0.417 s |
| Temurin HotSpot, Groovy-Quellstart | 894.1 ms | 0.688 s |

Die Rechenprobe enthält 100000 dynamische Closure-Aufrufe ohne `@CompileStatic`; das H2-Demo enthält externe SQL-Datei, Dateimessung, Parameter und Prepared Statement.

| Variante | H2-Demo, Minimum–Maximum | Rechenprobe, Minimum–Maximum |
|---|---:|---:|
| Crema-Interpreter | 94.5–155.5 ms | 2.213–4.251 s |
| Crema mit Ristretto-JIT | 115.0–240.7 ms | 1.422–1.833 s |
| Temurin HotSpot, vorkompiliertes Job-JAR | 580.0–664.6 ms | 0.348–0.458 s |
| Temurin HotSpot, Groovy-Quellstart | 844.3–1001.9 ms | 0.627–0.934 s |

Alle 80 gemessenen Prozesse und die 24 Vorläufe endeten erfolgreich. Die Streuung, besonders beim Interpreter-Rechenjob, begrenzt die Genauigkeit der konkreten Faktoren; die Rangfolge in diesen zwei Proben ist dennoch deutlich.

## Was ist beim JVM-Start enthalten?

Die Messung startet `time.perf_counter()` **vor** `subprocess.run(java, ...)` und endet erst nach Rückkehr des beendeten Prozesses. Enthalten sind:

- Erzeugen des Betriebssystemprozesses und Initialisieren der JVM.
- Klassenladen, Initialisieren von Groovy/Runner/Taskbibliotheken und gegebenenfalls JIT-Kompilierung während des Jobs.
- Laden der Jobdefinition, Parameterauflösung und vollständige Taskarbeit.
- Beim Quellstart zusätzlich das Parsen und Kompilieren des Groovy-Quellcodes.
- Beenden des Java-Prozesses einschliesslich seines normalen Shutdownpfads.

Jede Probe startet eine neue JVM. Es gibt weder eine dauerhaft laufende JVM noch einen Daemon oder mehrere Jobs in derselben JVM. Die drei Vorläufe wärmen insbesondere Dateisystem-/Bibliotheks-Caches; sie übertragen keinen JIT-Code in die folgenden JVMs. Die JDK-eigenen Standardeinstellungen einschliesslich des üblichen Class-Data-Sharing bleiben aktiv. Es gibt keinen anwendungsspezifisch trainierten AOT-/CDS-Cache.

Beim Job-JAR ist dessen vorherige Groovy-Kompilierung nicht enthalten: Das JAR wird vor der Messung erzeugt. Dafür gibt es die zusätzliche Quellstart-Zeile. Diese Unterscheidung gilt ebenso für die nativen Varianten, die ausschliesslich vorkompilierte Job-JARs ausführen.

Auch die **bereits dokumentierten** JVM-Zeiten aus dem ersten Versuch wurden vor dem Erzeugen des Java-Prozesses bis nach seinem Ende gemessen. Der JVM-Start wurde dort ebenfalls mitgemessen. Die Differenz zur Native-Zeit ist allerdings keine isolierte JVM-Startzeit: Auch Klasseninitialisierung, JIT-Anlauf und eigentliche Ausführung unterscheiden sich.

## Schlüsse für «JVM starten → Job ausführen → Pod beenden»

1. **Native ist bei kurzen Jobs ein ernsthafter Startzeitvorteil.** In diesem H2-Demo spart der Interpreter gegenüber einem JVM-Job-JAR rund 503 ms, gegenüber dem JVM-Quellstart rund 793 ms pro Prozess. Das ist ein lokaler Nachweis für diesen kleinen Job. Ob das fachlich relevant ist, hängt davon ab, wie oft solche Jobs laufen und wie wichtig einige hundert Millisekunden sind.
2. **Kurzlebigkeit schliesst einen JVM-JIT-Vorteil nicht aus.** Beim Rechenjob ist die gewöhnliche JVM mit Job-JAR trotz vollständigem Start rund 3.70-mal schneller als Ristretto und 5.90-mal schneller als der native Interpreter. Selbst der JVM-Quellstart ist hier schneller als beide Native-Varianten. «Ein neuer Pod pro Job» begründet deshalb allein keinen Wechsel auf Native.
3. **Ristretto verbessert den Interpreter, erreicht in dieser Probe aber die normale JVM nicht.** Im separaten Ristretto-Diagnoselauf wurden wieder 36 Codeinstallationen beobachtet. Ein weiterer, nicht zeitgemessener HotSpot-Lauf zeigt die Rechenclosure bereits nach rund 231 ms auf Optimierungsstufe 4. Die normale JVM optimiert also schon innerhalb dieses einzelnen kurzlebigen Jobs. Das Protokoll belegt JIT-Aktivität; es isoliert nicht die Ursachen des gesamten Laufzeitunterschieds.
4. **Vorabkompilieren ist unabhängig von Native interessant.** Der JVM-Job-JAR-Start spart in den beiden Beispielen gegenüber dem JVM-Quellstart rund 290 beziehungsweise 271 ms. Für selten geänderte Jobs ist das ein einfacher, bereits getesteter Optimierungspfad innerhalb der JVM-Variante.
5. **SQL- und Groovy-Arbeit getrennt beurteilen.** Der dynamische Job-Bytecode wird bei Crema interpretiert oder von Ristretto kompiliert; Runner, SqlExecutor und H2 stecken bereits als AOT-Code im Native Image. Das H2-Demo fordert nachgeladenen Groovy-Code nur wenig. Die synthetische Rechenprobe belastet ihn stark. Aus beiden Proben folgt keine allgemeine Beschleunigung produktiver ETL- oder serverseitiger PostgreSQL-Abfragen.

Meine Empfehlung aus diesen Messungen: **Die JVM bleibt die Ausgangsvariante für allgemeine GRETL-Jobs; Job-JARs als einfache Startoptimierung prüfen.** Den nativen Interpreter gezielt für sehr kurze, kompatible Jobs weiterverfolgen. Ristretto ist ein zusätzlicher Versuch für viel dynamische Groovy-Arbeit, aber anhand dieser Zahlen kein allgemeiner Grund, die normale JVM zu ersetzen.

### Grenze zur gesamten OpenShift-Zeit

Die Messung erfasst den Prozessanteil eures Betriebsmodells, nicht die gesamte Zeit von Pod-Erstellung bis Pod-Löschung:

```text
Zeit bis Jobende = Podbereitstellung + gemessener Prozessstart samt Job
Zeit bis Pod weg = Zeit bis Jobende + Podabbau
```

Nicht gemessen wurden Scheduling, Image-Download, Container-/Netzwerk-/Volume-Vorbereitung und anschliessender Podabbau. Kubernetes unterscheidet diese Phasen ausdrücklich; zu `Pending` zählen auch Scheduling und Image-Download. [Pod-Lebenszyklus](https://kubernetes.io/docs/concepts/workloads/pods/pod-lifecycle/)

Die Dateisystem-Caches dieser lokalen Messung sind warm. Ein neuer Pod bedeutet nicht automatisch einen erneuten vollständigen Image-Download: Das hängt unter anderem von Pull-Policy und vorhandenen Image-Layern auf dem Node ab. [Image-Pull-Verhalten](https://kubernetes.io/docs/concepts/containers/images/)

Damit kann ein grosser relativer Gewinn im Prozessanteil auf die gesamte Podlaufzeit deutlich kleiner wirken. Auch Binary-Grösse ist nicht gleich Containerimage-Grösse: Der Prototyp benötigt weiterhin dylibs und JDK-Klassen. Pullkosten, CPU-Zeit und Speicherbedarf wurden hier nicht gemessen. Aus den Laufzeiten allein lässt sich keine Ressourcen- oder Kostenersparnis ableiten.

Vor einer Produktionsentscheidung würde ich dieselben echten Jobs unter Linux im Ziel-OpenShift mit euren CPU-/RAM-Limits messen: Prozessstart bis Jobende sowie Pod-Erstellung bis Jobende getrennt, jeweils mit gecachtem und zunächst fehlendem Image. Die vorliegenden macOS-Zahlen ersetzen diesen Nachweis nicht.

## Reproduzieren und Nachweise

Die Runtime-JARs und beide nativen Binaries stammen unverändert aus dem Ristretto-Versuch. Für eine vollständige Neuerstellung zuerst `./mvnw verify`, `./scripts/build-native.sh` und `./scripts/build-native.sh --jit` ausführen. Der neue Vergleich lautet:

```bash
./scripts/setup-benchmark-jvm.sh
./scripts/benchmark-jvm.sh
```

Optional `CREMA_JVM_HOME` auf eine passende normale HotSpot-JVM setzen. Das Skript lehnt eine JVM mit aktiviertem Graal-/JVMCI-Compiler ab. Normale Java-Flags wurden für diese Messung nicht angepasst.

[Rohwerte, JVM-Einstellungen und Prüfsummen](results/jvm-comparison.json) · [HotSpot-Kompilierung der Jobclosures](results/hotspot-job-compilation.log) · [vorheriger Ristretto-Vergleich](RISTRETTO.md)

Vollständige aktuelle Ausgaben liegen in `crema-runner/target/benchmark-jvm/`. Alle Messungen und Anpassungen gehören zu diesem Repo; `../gretl-runtime` wurde von diesem Auftrag nicht verändert.
