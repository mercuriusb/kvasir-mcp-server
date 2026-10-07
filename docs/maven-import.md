# Maven-Abhängigkeiten nach kvasir bringen

Wie die Sources-JARs eines Java-Projekts automatisiert in `docs.data-dir` landen. Umgesetzt in
`scripts/import-maven-sources.sh`; die Ingestion selbst ist unberührt und in
[`ingestion.md`](ingestion.md) beschrieben.

## Warum nicht einfach die direkten Dependencies

Der naheliegende Griff ist

```bash
mvn dependency:copy-dependencies -Dclassifier=sources -DexcludeTransitive=true
```

und er ist bei einem Quarkus- oder Spring-Projekt **die falsche Menge**. Nachgemessen an diesem
Repository — die drei größten direkten Dependencies liefern:

| direkte Dependency                                | Inhalt der Sources-JAR                                       |
|---------------------------------------------------|--------------------------------------------------------------|
| `quarkus-hibernate-search-standalone-elasticsearch` | 17 `.java`: `SearchExtension`, `…Recorder`, `…BuildTimeConfig` |
| `quarkus-rest-jackson`                              | 23 `.java`: `SecureField`, `JacksonMapperUtil`                 |
| `quarkus-smallrye-health`                           | 14 `.java`                                                     |

Eine Extension ist Klebstoff zwischen Build und Bibliothek. Was man beim Programmieren tatsächlich
aufschlägt — `hibernate-search-mapper-pojo-standalone`, `hibernate-search-engine`,
`langchain4j-core`, `jakarta.ws.rs-api` — steht eine Ebene tiefer und fehlt bei `--direct`
vollständig. Umgekehrt zieht `--all` den kompletten Baum herein, samt Dingen, die nie jemand liest.

## Die brauchbare Menge: was importiert wird

`--used` beantwortet die Frage, die eigentlich gemeint ist: *welche Bibliotheken rufe ich auf?*

1. Aus allen `.java` des Projekts werden die `import`-Anweisungen gelesen und auf Package-Namen
   verkürzt.
2. `dependency:build-classpath` liefert die bereits aufgelösten **Binär**-JARs aus dem lokalen
   Repository. Deren Packages werden aus den `.class`-Einträgen abgeleitet — das kostet keinen
   Download, weil der Build sie ohnehin schon geholt hat.
3. Wo sich beide Mengen schneiden, wird die Sources-JAR geholt.

An diesem Repository: 87 importierte Packages, 38 Artefakte statt 14. Der Quarkus-Glue fällt
heraus, die Hibernate-Search-Familie kommt herein.

Der eine Fall, den das nicht trifft, ist Benutzung **ohne** `import` — SPI, Reflection,
Konfiguration. `aws-java-nio-spi-for-s3` wird hier über den NIO-Provider-Mechanismus geladen und
nie importiert, verschwindet also aus der Auswahl. Dafür gibt es `--include`:

```bash
scripts/import-maven-sources.sh --used \
    --include software.amazon.nio.s3:aws-java-nio-spi-for-s3 \
    --index-url http://localhost:8080 /pfad/zum/projekt
```

## Layout: ein kvasir-Projekt je Artefakt

```
<data-dir>/<artifactId>/<version>/<artifactId>-<version>-sources.jar
```

Die Alternative wäre, alle Dependency-JARs unter `<deine-app>/<app-version>/` abzulegen —
technisch möglich, denn `DataScanner` läuft rekursiv über das Versionsverzeichnis und erzwingt
entgegen dem Wortlaut von `CLAUDE.md` keine einzelne JAR. In die Chunk-ID geht der
projektrelative Pfad ein, mehrere Archive nebeneinander kollidieren also nicht.

Gewählt ist trotzdem ein Projekt je Artefakt:

- **Sources sind pro Version unveränderlich.** `jackson-databind 2.22.1` wird einmal eingebettet
  und von jeder App mitbenutzt. Beim App-Layout würde jeder Versionsbump der eigenen Anwendung
  denselben Bibliotheksbestand erneut einbetten.
- **`list_versions` wird sinnvoll.** Es liefert dann die Versionen einer Bibliothek — genau das,
  wofür die semantische Sortierung aus `CLAUDE.md` gebaut ist.
- **`get_class_source` bleibt eindeutig.** `ClassSourceLookup.sourceOf` holt mit `fetchHits(1)`;
  läge dieselbe FQCN in zwei Archiven desselben Projekts (Shading, `jsr305` neben `findbugs`),
  entschiede der Zufall.

Die Herkunft bleibt in beiden Fällen sichtbar: `source` eines Chunks ist
`<version>/<jar>!/<entry>`, die Bibliothek samt Version steht also in jedem Treffer.

## BOMs: nein

Ein BOM verwaltet Versionen, es sagt nichts über Benutzung. Gezählte
`dependencyManagement`-Einträge:

| BOM                       | verwaltete Artefakte |
|---------------------------|----------------------|
| `quarkus-bom` 3.33.3.1    | **2134**             |
| `quarkus-langchain4j-bom` | 213                  |
| `jackson-bom` 2.21.x      | 70                   |
| `quarkus-mcp-server-bom`  | 19                   |

Von den 2134 Artefakten in `quarkus-bom` benutzt dieses Projekt 38. Der Rest wäre Index ohne
Leser. Der entscheidende Schaden ist dabei nicht der Platz, sondern dass `list_projects` — laut
Tool-Description „source of truth for valid project values" und ohne `limit` vollständig — zu
einem Katalog aus tausenden Bibliotheksnamen würde. Damit ist es für den Agenten keine
Entscheidungshilfe mehr.

Bei einem kleinen Produkt-BOM (`jackson-bom`, `quarkus-mcp-server-bom`) wäre es vertretbar. Auch
dort trifft `--used` die bessere Menge.

## Was das kostet

Gemessen an den 38 Artefakten dieses Projekts, mit den echten Parsern und dem konfigurierten
Modell (`multilingual-e5-small`, MEAN-Pooling):

| Größe                                    | Wert                        |
|------------------------------------------|-----------------------------|
| Sources-JARs auf Platte                  | 8,6 MB                      |
| `.java`-Dateien                          | 5612                        |
| Chunks gesamt                            | 36 957                      |
| davon `javadoc` / `package-doc` / `source` | 31 984 / 44 / 4929        |
| **einzubetten** (ohne `source`)          | **32 028**                  |
| Textmenge der eingebetteten Chunks       | 4,0 MB, Median **63 Zeichen** |
| Textmenge der `source`-Chunks            | 22 MB                       |
| Vektoren                                 | 32 028 × 384 × 4 B = 46 MB  |
| Parsen aller 38 Archive                  | 5,0 s                       |

Der Median von 63 Zeichen ist die wichtigste Zahl: die meisten `javadoc`-Chunks sind eine
Methodensignatur ohne Kommentartext. Deshalb dominiert der Fixaufwand je Inferenz, und mehr Kerne
bringen wenig:

| Kerne | Chunks/s | 32 028 Chunks |
|-------|----------|---------------|
| 1     | 60       | 8,9 min       |
| 6     | 125      | **4,3 min**   |
| 14    | 184      | 2,9 min       |

Sechs Kerne liefern also gut das Doppelte eines einzelnen, nicht das Sechsfache.

Das Schreiben in OpenSearch ist hier nicht gemessen worden (keine Instanz verfügbar). Bei
insgesamt ~72 MB Nutzlast auf 37 000 Dokumenten ist es gegenüber dem Einbetten aber klein.

Der Aufwand fällt **einmal** an: der nächste Scan überspringt jede Datei per Fingerabdruck, und
Sources-JARs ändern sich innerhalb einer Version nicht mehr. Neu eingebettet wird nur bei einer
neuen Bibliotheksversion oder beim Hochzählen von `IndexSchema.VERSION`.
