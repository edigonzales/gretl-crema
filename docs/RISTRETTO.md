# Ristretto-JIT im Vergleich zum Crema-Interpreter

Prüfdatum: 26. Oktober 2026. **Beide Varianten funktionieren.** Ristretto verringert die Prozesslaufzeit des dynamischen Groovy-Rechenjobs um 34.3 % (Faktor 1.52); beim kurzen H2-Demo zeigt sich kein Vorteil.

## Aufbau und Funktionsnachweis

- Gleicher Drei-Modul-Build, gleiche Job-JARs, gleiche Taskbibliotheken, Groovy 6.0.0 und H2 2.3.232.
- Gleiche Oracle GraalVM 25.4.4.1.1, macOS 27.0.1 ARM64, Apple M5, 24 GiB RAM.
- Einzige funktionale Builddifferenz: `-H:-GraalJITCompileAtRuntime` beim Interpreter und `-H:+GraalJITCompileAtRuntime` bei Ristretto. RuntimeClassLoading, Preserve, JRT-Zugriff und Optimierungsstufe sind identisch. Der gemeinsame CLI-Versionstext wurde neutral formuliert; die Ausführungslogik blieb unverändert.
- Beide Varianten bestehen alle **12 nativen Abnahmetests**, einschliesslich Java/Groovy-Klassen, GRETL-DSL, H2-Datenprüfung, später kompiliertem zweiten Job, externen SQL-Änderungen und Fehlerfällen.
- `./mvnw verify` besteht mit **204 Tests**, ohne Fehler oder übersprungene Tests.
- Jobklassen werden nach dem Native-Build kompiliert und sind weder im Image-Classpath noch in statischen Job-Metadaten enthalten. Beide Binary-Prüfsummen blieben bei Abnahme und Messung unverändert.

Native-Image-Erweiterungen für die Groovy-Jobs waren gegenüber dem Interpreterversuch nicht nötig. Ristretto ist laut [versionsgleicher Crema-Dokumentation](https://github.com/oracle/graal/blob/vm-25.4.4.1.1/substratevm/docs/runtime-class-loading.md) über den genannten Buildschalter aktivierbar.

## Laufzeiten

Je Kombination aus Variante und Job: **drei Vorläufe und zehn gemessene frische Prozesse**. Pro Runde rotiert die Reihenfolge der drei Varianten. Gemessen wird vom direkten Prozessstart bis zum Prozessende, einschliesslich Jobdefinition, Taskarbeit und gegebenenfalls JIT-Kompilierung. Es gibt keine parallelen Native-Builds während der Messung. Shellstart und Toolchain-Prüfung sind nicht enthalten.

| Variante | Kurzes H2-Demo, Median | Dynamisches Groovy, Median |
|---|---:|---:|
| Crema-Interpreter | 127.0 ms | 2.118 s |
| Ristretto-JIT, Voreinstellungen | 136.3 ms | 1.393 s |
| Ristretto-Binary, JIT ausgeschaltet | 133.4 ms | 2.090 s |

Das H2-Demo ist derselbe Job wie beim ersten Versuch: externe SQL-Datei, FileSize-Vorgänger, Parameter und Prepared Statement. Die Streuung überlappt deutlich: Interpreter 76.0–153.7 ms, Ristretto 80.6–153.7 ms. Aus diesen Messungen ergibt sich kein belastbarer JIT-Vorteil für diesen kurzen Job.

Der Rechenjob führt **100000 dynamische Closure-Aufrufe** ohne `@CompileStatic` aus. Die unabhängig in Python berechnete Kontrollsumme `326690737` wird in jedem Prozess geprüft. Gemessene Spannweiten: Interpreter 2.045–2.636 s, Ristretto 1.288–1.818 s. Das Ristretto-Binary mit deaktivierter Kompilierung liegt nahe am reinen Interpreter; der Unterschied des aktivierten JIT passt damit zum Effekt der tatsächlichen Kompilierung.

Die Rechenfixture erlaubt auch längere Läufe. Ein zusätzlicher Funktionslauf mit einer Million Wiederholungen lieferte korrekt `1164237905`; er gehört nicht zu dieser Messreihe.

## Tatsächliche JIT-Aktivität

Zusätzliche Diagnoseläufe mit `-XX:+JITTraceCompilation`, `-XX:+JITTraceCodeInstallations` und `-XX:+JITPrintExceptions` sind von den Zeitmessungen getrennt:

- H2-Demo: 0 abgeschlossene JIT-Kompilierungen und 0 Codeinstallationen beobachtet.
- Groovy-Rechenjob: 36 abgeschlossene Kompilierungen, 36 Codeinstallationen, 28608 Bytes installierter Code, darunter 1 Schleifeneinstieg per On-Stack Replacement.
- Die geschachtelte Rechenclosure und die äussere GRETL-Task-Closure werden beide JIT-kompiliert. Der Schleifeneinstieg liegt in `hotloop$_run_closure1.doCall(Object)` bei Bytecodeposition 33.
- Keine Kompilierungsfehler oder Bailouts im Diagnoseprotokoll beobachtet.

Die Voreinstellungen wurden nicht verändert. Die mitgelieferte `RistrettoOptions.java` in GraalVMs `lib/svm/builder/svm.src.zip` nennt 1000 Methodenaufrufe als Aufrufschwelle und 30000 Schleifenrücksprünge als OSR-Schwelle. Ein neuer Prozess beginnt wieder ohne den JIT-Code seines Vorgängers.

Kompakte [Codeinstallationsnachweise](results/ristretto-code-installations.log); vollständige Diagnoseausgaben liegen in `crema-runner/target/benchmark-jit/*-jit-trace.log`.

## Grösse, Bauzeiten und Grenzen

| Binary | Grösse | Protokollierte Bauzeit |
|---|---:|---:|
| Interpreter | 178.09 MiB | 196 s |
| Ristretto | 219.23 MiB | 180 s |

Ristretto vergrössert das Binary um 23.1 %. Dazu kommen weiterhin die erzeugten dylibs und die JDK-Klassen aus `lib/modules`. Die Bauzeiten sind Einzelprotokolle, kein Buildbenchmark; während eines Teils des Interpreterbaus lief noch eine Vorab-Diagnose. Beide Builds wurden vor Beginn der eigentlichen Laufzeitmessung abgeschlossen.

Die Messung verwendet warme Dateisystem-Caches und frische Prozesse; sie ist keine Messung eines kalten Betriebssystem-Caches. Der Rechenjob ist eine synthetische Bytecode-Probe und kein fachlicher ETL-Benchmark. Daraus folgt weder eine allgemeine Beschleunigung von SQL/H2 noch eine Aussage über produktive lange Jobs. Für kurze Jobs bleibt der Interpreter in diesem Versuch die einfachere Variante; bei häufig ausgeführtem dynamischem Groovy-Code ist Ristretto ein nachgewiesener Kandidat für weitere reale Jobmessungen.

## Reproduzieren

```bash
./mvnw verify
./scripts/build-native.sh
./scripts/build-native.sh --jit
./scripts/verify-native.sh
./scripts/verify-native.sh --jit
./scripts/benchmark-jit.sh
```

Eigene vorkompilierte Jobs starten über `./bin/gretl-crema-jit run ...` mit denselben Argumenten wie beim Interpreter. `./bin/gretl-crema-jit -XX:-JITEnableCompilation run ...` ist der Kontrollmodus. Der Interpreter bleibt weiterhin unter `./bin/gretl-crema` verfügbar. `../gretl-runtime` wurde von diesem Auftrag nicht verändert.

Maschinenlesbare Nachweise: [Rohmesswerte und Binary-Prüfsummen](results/ristretto-comparison.json), [Interpreter-Abnahme](results/acceptance-interpreter-v2.json), [Ristretto-Abnahme](results/acceptance-jit.json). Die [erste Interpreter-Messung](PRUEFBERICHT.md) bleibt als historischer Prüfstand erhalten.
