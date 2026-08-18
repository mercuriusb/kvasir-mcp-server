# Projekt: Kvasir (kvasir-mcp-server)

Ein MCP-Server (Quarkus) für Java-Projekte, der **Markdown-Docs, Javadoc und Java-Quellcode**
über eine hybride Suche (BM25 + Vektor/kNN via OpenSearch) durchsuchbar macht — projekt- und versionsbewusst, für
AI-Agenten wie Claude Code.

## Ziel

AI-Agenten sollen per MCP-Tool präzise, **projekt- und versionsspezifische** Antworten aus Doku, Javadoc und Sourcecode
mehrerer Java-Projekte abrufen können, statt aus dem Trainingsstand zu raten oder zu halluzinieren.

## Tech-Stack

- **Quarkus 3.x**, Java 25 (Minimum)
- **MCP**: `io.quarkiverse.mcp:quarkus-mcp-server-http` (Streamable HTTP + SSE, deklarative
  `@Tool`/`@Resource`-Annotationen, build-time optimiert, native-fähig). Zusätzlich
  `quarkus-mcp-server-stdio` optional für lokale Claude-Desktop-Nutzung.
- **Embeddings**: `io.quarkiverse.langchain4j:quarkus-langchain4j-core` — Embedding-Modell läuft in-process via ONNX
  Runtime, **konfigurierbar austauschbar** (siehe unten). Kein externer Modell-Server, keine API-Kosten.
- **Suche/Storage**: `io.quarkus:quarkus-hibernate-search-standalone-elasticsearch` — vereint Volltext (BM25) und
  Vektor/kNN in einer deklarativen API, Backend per Konfiguration austauschbar zwischen Elasticsearch und OpenSearch
  (siehe unten). **Standard-Backend: OpenSearch** — es wird die vorhandene OpenSearch-Instanz verwendet, keine
  eigene/lokale Instanz aufgesetzt.
- **Source-Parsing**: JavaParser (`.java`-Dateien → Klassen/Methoden/Javadoc-Kommentare als eigene Chunks)
- **Markdown-Parsing**: flexmark-java, Chunking entlang von Überschriften
- **Javadoc-HTML**: Jsoup (generierte Javadoc-Seiten → Klassenbeschreibung, Methoden-Signaturen, `@since`-Tags zur
  Versionszuordnung)

## Kernanforderung 0: Datenquellen-Layout (`data`-Ordner)

> Ablauf, Klassenrollen und Zwischenspeicherung der Umsetzung: [`docs/ingestion.md`](docs/ingestion.md)

Alle Quellen werden zunächst über ein lokales Verzeichnis eingespielt, kein Crawling:

```
data/
  <project>/
    doc/                          ← versionsübergreifende Doku
      *.md  *.txt  *.html
    <version>/
      <project>-<version>-sources.jar   ← genau eine Sources-JAR
      *.md  *.txt  *.html         ← versionsspezifische Doku
```

Ingestion-Regeln:

| Quelle                                  | Herkunft                                                                                    | `project` | `version`                           | Chunking                                                                                                                                     |
|-----------------------------------------|---------------------------------------------------------------------------------------------|-----------|-------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------|
| `.jar` unter `<version>/`               | Sources-JAR, wird über das Zip-`FileSystem` gelesen, alle `.java`-Dateien via JavaParser geparst | aus Pfad  | aus Pfad                            | pro Klasse: Javadoc + Signaturen, siehe Kernanforderung 4                                                                                    |
| `.md`/`.txt`/`.html` unter `<version>/` | versionsspezifische Doku                                                                    | aus Pfad  | aus Pfad                            | Markdown wie bisher (flexmark, nach Überschrift); TXT: feste Wortfenster (~300 Wörter) ohne Struktur; HTML: Jsoup, Chunking nach `<h1>-<h3>` |
| `.md`/`.txt`/`.html` unter `doc/`       | versionsübergreifende Doku                                                                  | aus Pfad  | `null` (Sentinel: gilt projektweit) | wie oben                                                                                                                                     |

**Suchverhalten bei versionsübergreifenden Chunks**: `search_docs` filtert intern auf
`project = X AND (version = Y OR version IS NULL)`, damit `doc/`-Inhalte bei jeder Versionsabfrage mitgefunden werden,
ohne pro Version dupliziert zu werden. Treffer aus
`doc/` müssen im Ergebnis erkennbar als "versionsübergreifend" markiert sein (`version:
null` im Rückgabeobjekt), damit der Agent das nicht mit einer versionsspezifischen Aussage verwechselt.

Ein Scan-Vorgang (ausgelöst über den REST-Endpoint `POST /admin/index`, siehe eigener Abschnitt, oder einen
Startup-Scan) durchläuft
`data/**` einmal vollständig; bereits indexierte, unveränderte Dateien werden anhand Pfad+Hash übersprungen (kein
Neu-Embedding bei jedem Start).

**Umsetzungsdetails der Ingestion**

- **Ein Archiv ist ein Parser wie jeder andere.** `DocumentParser.parse(SourceLocation, Path)` bekommt die
  Datei, nicht deren dekodierten Text — nur deshalb kann eine Sources-JAR (die kein Text ist) über dieselbe
  Schnittstelle laufen. Der Scanner fragt lediglich, wer eine Datei beansprucht; es gibt keinen Sonderpfad und
  keine Endungsliste in ihm. Textbasierte Parser erben das Lesen von `TextDocumentParser`.

  Das Öffnen selbst liegt in `ArchiveReader` mit je einem `ArchiveOpener` pro Format. Aktuell gibt es
  `ZipArchiveOpener` (`.jar`, `.zip`); **weitere Formate wie tar sind ein zusätzlicher Opener und sonst
  nichts** — weder Scanner noch Parser noch Ingestor ändern sich, weil alle nur den `Path`-Baum sehen, den
  `Archive` aufspannt. Ein Opener meldet über `requiresRandomAccess()`, ob sein Format springt: Zip hat sein
  Verzeichnis am Dateiende und muss seeken, ein tar wird von vorn nach hinten gelesen und könnte direkt vom
  Remote-Storage gestreamt werden.

  Chunks aus einem Archiv tragen als `source` die Form `<archive>!/<entry>`, damit sie beim Wechsel des
  Archivs gemeinsam entfernt werden können. Das Trennzeichen steht **einmal** in `SourceLocation`; der
  Ingestor fragt dort nach dem Präfix, statt es selbst zu tippen — zwei Kopien würden auseinanderlaufen und
  lautlos Waisen im Index hinterlassen.
- **Der Hash wird streamend gebildet** (`DigestInputStream`), nicht über ein `byte[]` des Dateiinhalts. Eine
  Sources-JAR beliebiger Größe landet damit nie im Heap, und sie wird auch nur einmal gelesen — den Inhalt
  holt sich der Parser selbst.
- **Der Fingerabdruck umfasst auch die Schema-Version** (`IndexSchema.VERSION`), nicht nur den Inhalts-Hash.
  Ändert sich die Art, wie indexiert wird — Mapping, Embedding-Modell, Chunking-Regeln —, sind die
  gespeicherten Chunks veraltet, obwohl die Dateien selbst unverändert sind und ihr Hash ebenfalls. Das
  Hochzählen der Konstante lässt beim nächsten Scan alle Dateien als geändert gelten. Ohne das müsste jemand
  daran denken, den Fingerabdruck-Index von Hand zu löschen — und genau dieses Vergessen erzeugt den
  gefährlichsten Zustand des Systems (siehe unten).
- **Gelöschte Dateien werden entfernt.** Ein Scan kann eine gelöschte Datei nie sehen, er läuft nur über
  Vorhandenes. Er merkt sich deshalb die angetroffenen Dateien und entfernt am Ende alles, was als indexiert
  vermerkt ist, ihm aber nicht begegnet ist — nur bei fehlerfreiem Durchlauf des Projekts, und eine Datei,
  deren Verarbeitung scheitert, gilt als angetroffen.
- **Der Pfad+Hash-Speicher ist ein zweites indexiertes Entity** `IndexedFile` (SHA-256 des Dateiinhalts). Er
  liegt im Suchindex, weil eine separate Datenbank für Rohdaten ein Nicht-Ziel ist und der Index Neustarts
  ohnehin übersteht.
- **Vor dem Schreiben werden die bisherigen Chunks einer Datei entfernt.** Chunk-IDs sind positionsbasiert;
  ohne das Löschen bliebe bei einer schrumpfenden Datei der Überhang als Waise im Index zurück.
- **Fehler werden je Projekt und je Datei isoliert.** Eine unlesbare Datei erhöht `failedFiles`, ein
  Projektverzeichnis, das sich nicht durchlaufen lässt, `failedProjects` — in beiden Fällen läuft der Scan
  weiter. Ein kaputtes Projekt darf die anderen nicht mitnehmen.
- **`source`-Chunks bekommen bewusst keinen Vektor.** Sie existieren, damit `get_class_source` eine ganze
  Datei zurückgeben kann, und werden über den Klassennamen geholt, nie über semantische Ähnlichkeit. Ein
  Embedding über eine komplette Quelldatei wäre ohnehin nur ein Embedding ihrer ersten paar hundert Token.

### Datenquelle: aktuell lokal, S3 als spätere Alternative

Der Wurzelpfad ist konfigurierbar, nicht hart codiert:

```properties
docs.data-dir=data
```

Der Scanner arbeitet **ausschließlich über die Java-NIO-`Path`/`FileSystem`-Abstraktion**
(`Files.walk`, `Files.readAllBytes` etc.), nicht über quellsystem-spezifische APIs. Das ist bewusst so gehalten, damit
`docs.data-dir` später ohne Änderung am Parser-/Ingestion- Code auf einen S3-Bucket zeigen kann — entweder über ein
S3-kompatibles NIO-Filesystem (z. B. `s3fs`/Mountpoint für S3 als gemounteter Pfad) oder über einen vorgelagerten,
eigenständigen Sync-Schritt, der Artifactory/Wiki/S3-Inhalte einmalig in dieselbe
`<project>/<version>/`-Struktur unter `docs.data-dir` kopiert, bevor gescannt wird.

**Umgesetzt als `DataStore`** (`org.kvasir.storage`): die einzige Klasse, die überhaupt weiß, wo die Daten
liegen. Sie löst `docs.data-dir` auf und gibt nach unten nur noch ein `Path` weiter — Scanner und Parser
erfahren nie, von welchem Dateisystem es stammt.

```properties
docs.data-dir=data                       # gewöhnlicher Pfad, Default-Dateisystem
docs.data-dir=s3://mein-bucket/java-docs # URI: der NIO-Provider des Schemas wird benutzt
```

Enthält der Wert ein Schema, wird er an `FileSystems.newFileSystem(URI, Map)` gegeben; der passende
NIO-Provider muss dann im Classpath liegen. Fehlt er, bricht der Start mit einer Meldung ab, die Schema und
Property nennt (`DataStore` wird über `@Startup` bewusst eager erzeugt, damit eine falsche Konfiguration nicht
erst beim ersten Scan auffällt).

**Sources-JARs sind der einzige Sonderfall.** Der Zip-Provider braucht wahlfreien Zugriff auf das Archiv. Auf
lokaler Platte ist das unproblematisch, aus einem Object Store wären es tausende Range-Requests. `DataStore`
kopiert deshalb jedes Archiv, das *nicht* auf dem Default-Dateisystem liegt, in einem sequentiellen Lesevorgang
in eine lokale Temp-Datei und öffnet den Zip-`FileSystem` darauf; beim Schließen des `Archive` verschwindet die
Kopie wieder. Auf lokaler Platte entfällt dieser Schritt.

**Gegengetestet**: `RemoteFilesystemScanTest` lässt einen vollständigen Scan inklusive Sources-JAR gegen ein
In-Memory-NIO-Dateisystem (Jimfs) laufen — stellvertretend für jedes Nicht-Default-Dateisystem. Damit ist
belegt, dass keine Stelle im Scanner still von lokaler Platte ausgeht.

**S3 als Datenquelle** ist eingebaut: `software.amazon.nio.s3:aws-java-nio-spi-for-s3` liegt im Classpath
und registriert zwei Schemata — `s3` für AWS und **`s3x` für eigene Endpunkte**, die dann aus der URI
kommen:

**S3 ist inzwischen die eingestellte Voreinstellung**, nicht mehr nur eine Möglichkeit —
`application.properties` zeigt auf den vorhandenen Store:

```properties
docs.data-dir=s3x://<host>/<bucket>
docs.data-dir-properties."s3.spi.force-path-style"=true
```

Zurück auf lokale Platte geht es über einen gewöhnlichen Pfad (`docs.data-dir=data`). Tests sind davon
nicht betroffen: `%test.docs.data-dir` zeigt auf den Fixture-Baum, ein Build braucht also weiterhin keine
Zugangsdaten.

Scanner und Parser bleiben unberührt; sie sehen weiterhin nur den `Path`-Baum.

Drei Dinge, die dabei experimentell festgestellt wurden und nicht offensichtlich sind:

- **Die `env`-Map von `FileSystems.newFileSystem(URI, Map)` wird vom Provider ignoriert.** Er liest seine
  Einstellungen aus System-Properties. `docs.data-dir-properties` setzt deshalb System-Properties, bevor
  das Dateisystem geöffnet wird — untypisiert, damit diese Anwendung das Vokabular keines Providers lernen
  muss. Ein explizit gesetztes `-D` gewinnt.
- **`s3.spi.force-path-style=true` ist für einen selbst gehosteten Store Pflicht.** Ohne das wird der
  Bucket als Hostname adressiert (`bucket.s3.example.com`) und die Anfrage stirbt in der DNS-Auflösung —
  also lange vor dem Endpunkt, was die Fehlersuche in die falsche Richtung schickt.
- **Zugangsdaten stehen nie als Wert in einer eingecheckten Datei**, sondern werden dort nur
  *referenziert*. `application.properties` bildet drei Namen auf die System-Properties ab, die der
  Provider tatsächlich liest:

  ```properties
  docs.data-dir-properties."aws.accessKeyId"=${AWS_ACCESS_KEY_ID:}
  docs.data-dir-properties."aws.secretAccessKey"=${AWS_SECRET_ACCESS_KEY:}
  docs.data-dir-properties."aws.region"=${AWS_REGION:us-east-1}
  ```

**Lokal: `.env`, und Quarkus liest sie von allein.** Die Werte liegen in einer `.env` im
Projektwurzelverzeichnis (in `.gitignore`, Vorlage: `.env.example`). Weil dieselbe Zeile oben auch echte
Umgebungsvariablen auflöst, deckt **eine** Abbildung beide Welten ab: `.env` beim Start aus der IDE,
Kubernetes-Secret im Cluster. Ein Start aus IntelliJ braucht damit keinerlei Vorbereitung.

Zwei Dinge dazu, die man sonst falsch erwartet:

- **`.env` füllt nicht die Prozessumgebung.** Quarkus liest sie in seine *eigene* Konfiguration; das
  AWS-SDK ruft aber `System.getenv()`, und die Umgebung einer laufenden JVM kann niemand mehr ändern,
  weil Java dafür keine API anbietet. Genau deshalb existiert die Abbildung oben. Wo doch direkt
  `System.getenv()` gilt — `S3DataStoreTest` und ein `java -jar` außerhalb des Projektverzeichnisses —,
  muss die Datei vorher in die Shell: `set -a; source .env; set +a` (`scripts/run-with-env.sh`).
- **Der leere Default ist Pflicht und zugleich eine Falle.** Ohne `:` bricht der Start ab, sobald die
  Variable fehlt. Mit leerem Default liest SmallRye den Wert als *fehlend* und scheitert bei der
  Konvertierung (`SRCFG00040`) — eine Installation ganz ohne S3 käme nicht mehr hoch. `DataStore` liest
  `docs.data-dir-properties.*` deshalb über `Config.getConfigValue(...)` roh aus, ohne Konverter, und
  behandelt einen leeren Wert als „nicht angegeben". Gesetzt wird er dann nicht: eine leere
  `aws.accessKeyId` würde die Zugangsdaten verdecken, die das SDK sonst selbst gefunden hätte.

**Gegen einen echten Store belegt** (selbst gehosteter MinIO-Fork): Ein vollständiger Scan aus S3 hat
11 Dateien und 2084 Chunks eingelesen, inklusive einer Sources-JAR — das Staging aus Kernanforderung 0
trägt also auch über Objektspeicher. Präfixe werden als Verzeichnisse erkannt, **ohne dass Marker-Objekte
nötig sind**.

Zwei Fallen, die dabei aufgefallen sind und die auf lokaler Platte nie auftreten:

- **`newFileSystem` legt bei diesem Provider den Bucket an** (`CreateBucket`). Gegen einen vorhandenen
  Bucket scheitert das mit `AccessDenied`, gegen einen neuen Ort würde es stillschweigend einen anlegen.
  `DataStore` fragt deshalb zuerst nach dem *vorhandenen* Dateisystem.
- **`FileSystemProvider.installedProviders()` sucht im System-Classloader**, Quarkus lädt
  Anwendungsabhängigkeiten aber in seinem eigenen. Im gepackten Jar wäre der Provider nicht gefunden
  worden, obwohl er darin liegt. `DataStore` sucht ihn deshalb über den Kontext-Classloader.
- **Verzeichnisnamen kommen mit Schrägstrich zurück** (`jackson/`). Ungefiltert würde ein Projekt als
  `"jackson/"` indexiert, und schlimmer: `doc/` würde nicht als versionsübergreifendes Verzeichnis
  erkannt, sondern als Version namens `doc/`. `DataScanner` schneidet den Trenner deshalb ab.

**Der Provider erzeugt sein Dateisystem lazy.** Eine falsche Konfiguration fällt daher nicht mehr beim
Start auf. `DataStore` prüft die Erreichbarkeit beim Start und **warnt**, bricht aber nicht ab: Suchen
rührt das Datenverzeichnis überhaupt nicht an, nur Indexieren. Ein unerreichbarer Objektspeicher darf das
Retrieval nicht mitreißen.

**Explizit nicht Teil von Phase 1**: kein Artifactory-Client, kein Wiki-Connector, kein S3-SDK-Aufruf im Scanner
selbst — diese bleiben eigenständige, später hinzufügbare Bausteine, die lediglich `docs.data-dir` befüllen, ohne den
Scanner/die Parser anzufassen. Eine zentrale Datenbank (z. B. PostgreSQL) für die Rohdokumente ist keine vorgesehene
Option — das wäre ein zusätzliches, redundant zu haltendes System ohne Mehrwert gegenüber Datei-basiertem Storage.

## Kernanforderung 1: Multi-Projekt- und Multi-Version-Support

- Jedes Chunk-Dokument trägt Pflicht-Metadaten: `project`, `version`, `type`
  (markdown/txt/html/javadoc/package-doc/source), `source` (Datei/Klassenpfad), optional `since`
- `type` ist im Code kein Freitext, sondern das Enum `ChunkType`. Die sechs Werte werden von vier
  verschiedenen Parsern gesetzt und sind gleichzeitig der Filterwert von `search_docs` — ein Tippfehler würde
  klaglos indexiert und den Chunk über den Filter dauerhaft unauffindbar machen. Im Index und in
  MCP-Antworten steht der „Wire Name" (`package-doc`, nicht `PACKAGE_DOC`); dafür sorgen eine
  `ValueBridge` auf dem `@KeywordField` und `@JsonValue` auf dem Enum
- Alle Chunks aller Projekte/Versionen liegen im **selben** Hibernate-Search-Index (kein separates Verzeichnis pro
  Projekt) — Trennung erfolgt ausschließlich über
  `@KeywordField`-Filter auf `project` + `version`
- Jede Suchanfrage filtert **zwingend** nach `project` und `version` — niemals Ergebnisse über Projekt-/Versionsgrenzen
  hinweg mischen
- `list_projects` und `list_versions(project)` sind für den Agenten die Quelle der Wahrheit für gültige Werte —
  `project`/`version` werden nicht als Freitext geraten

## Kernanforderung 2: Java-Source-Parsing inkl. Package-Javadoc

Pro entpackter Sources-JAR werden **zwei Arten von Javadoc** ausgewertet, nicht nur Klassen-Javadoc:

- **Klassen-/Methoden-Javadoc**: aus jeder `.java`-Datei mit einer öffentlichen Top-Level-Klasse/Interface/Enum —
  Klassenbeschreibung als ein Chunk, jede öffentliche Methode (Signatur + Javadoc) als eigener Chunk mit Bezug zur
  umschließenden Klasse (`fullyQualifiedClassName` in den Chunk-Metadaten).
- **Package-Javadoc**: aus `package-info.java`-Dateien (JavaParser unterstützt das Parsen der Package-Javadoc direkt aus
  der Compilation Unit). Falls stattdessen ein klassisches `package.html` vorliegt, wird dessen Inhalt per Jsoup
  extrahiert. Diese Chunks bekommen `type = "package-doc"` und den Package-Namen statt eines Klassennamens als `source`
  -Bezug — sie stehen in keiner Klasse und dürfen beim Parsen nicht einfach ausgelassen werden, nur weil sie an keiner
  `class`/`interface`- Deklaration hängen.

`get_class_source` bleibt auf Klassen bezogen; Package-Javadoc wird ausschließlich über
`search_docs` mit `type = "package-doc"` auffindbar.

`get_class_source` findet den Quelltext über `type = "source"` **und** `fullyQualifiedClassName`, streng
gefiltert auf `project` und `version`. Ein Rückfall auf eine andere Version ist hier die schlechteste
denkbare Antwort: der Aufrufer hat nach dem Stand eines bestimmten Release gefragt, und Quelltext, der bloß
plausibel aussieht, fällt später schwerer auf als ein Fehler. Findet sich nichts, nennt die Fehlermeldung
die Versionen, in denen die Klasse existiert — eine falsch geratene Version ist der wahrscheinlichste Weg
hierher.

**Dritter Chunk je Datei: der Quelltext selbst.** Neben den Javadoc-Chunks erzeugt der Java-Parser pro
öffentlicher Top-Level-Klasse einen Chunk mit `type = "source"`, der den vollständigen Dateiinhalt trägt und über
`fullyQualifiedClassName` auffindbar ist. Ohne ihn hätte `get_class_source` keine Datenquelle: die entpackte
Sources-JAR liegt nur in einem Temp-Verzeichnis, und eine Rohdaten-Datenbank ist ein Nicht-Ziel. Der Index ist
damit die einzige Stelle, an der der Quelltext dauerhaft liegt.

**Signaturen enthalten Typparameter.** JavaParsers `getDeclarationAsString` lässt sie weg — aus
`<T> T readValue(...)` würde `T readValue(...)`. Da die Signatur als `heading` des Chunks direkt beim Agenten
landet, werden die Typparameter wieder vorangestellt.

## Kernanforderung 3: Alle Begriffe bei kurzen Suchanfragen zwingend

Bei Suchanfragen mit **wenigen** Begriffen soll die Volltext-Komponente (BM25-Teil) nur Treffer liefern, die **alle**
Begriffe enthalten (AND-Semantik), statt der bei mehr Begriffen üblichen "die meisten passen"-Logik
(minimum-should-match). Grund: bei kurzen Queries wie `"jackson polymorphism deserialization"` sind Nutzer/Agenten meist
präzise, und ein Treffer, der nur einen von drei Begriffen enthält, ist selten hilfreich.

```properties
docs.search.exact-match-max-terms=3
docs.search.min-should-match-percent=75
```

- Zerlegte Anfrage mit ≤ `exact-match-max-terms` Begriffen → BM25-Query mit
  `minimumShouldMatch = 100%` (alle Begriffe erforderlich)
- Mehr Begriffe als der konfigurierte Wert → normales `minimumShouldMatch`
  (`docs.search.min-should-match-percent`), damit lange, natürlichsprachliche Anfragen nicht durch einen einzelnen
  selteneren Begriff leerlaufen
- Die kNN-/Vektor-Komponente bleibt von dieser Regel unberührt — sie liefert weiterhin semantisch ähnliche Treffer
  unabhängig von exakter Begriffsübereinstimmung; erst die Fusion (RRF) der beiden Ranglisten führt zum Endergebnis. Die
  AND-Pflicht gilt nur für den BM25-Teilscore, nicht für den kNN-Teilscore
- Begriffszählung erfolgt nach demselben Analyzer/Tokenizer wie bei der Indexierung (Stoppwörter etc. konsistent
  behandeln, sonst weicht die gezählte Begriffszahl von der tatsächlich für die Query relevanten Zahl ab)

## Kernanforderung 4: Fusion von BM25 und Vektor-Suche (RRF, konfigurierbar)

Die Kombination der BM25-Treffer (Volltext) und kNN-Treffer (Vektor) erfolgt **ausschließlich über OpenSearchs
eingebaute Reciprocal-Rank-Fusion (RRF)**, nicht über selbst berechnete Score-Summen. Wichtiger Unterschied: RRF
kombiniert **Rangpositionen**, nicht Rohwerte — dadurch entfällt das Problem, dass BM25-Scores (unbeschränkt) und
Cosine-Similarity (-1 bis 1) numerisch nicht vergleichbar sind.

```properties
docs.search.rrf.rank-constant=60
docs.search.rrf.weight-bm25=0.5
docs.search.rrf.weight-vector=0.5
```

- `rank-constant` (RRF-k): größere Werte glätten die Rangliste und schwächen den Einfluss von Top-Treffern ab, kleinere
  Werte verstärken ihn. 60 ist ein über viele Korpora hinweg bewährter Standardwert und die sinnvolle Voreinstellung.
- `weight-bm25` / `weight-vector`: optionale Gewichtung der beiden Teilrankings innerhalb der RRF-Fusion (Default je
  0.5 = gleichgewichtet).
- **Diese Werte werden 1:1 an OpenSearchs Search-Pipeline durchgereicht**
  (`score-ranker-processor`, Technik `rrf`, Felder `rank_constant` und `weights`) — Claude Code implementiert **keine
  eigene Formel**, die BM25- oder Cosine-Rohwerte einliest und verrechnet. Der Anwendungscode liest nur die Config-Werte
  und baut daraus die Pipeline-Definition beim Start bzw. bei Pipeline-Erstellung.
**Umsetzung**: `RrfPipeline` legt beim Start eine OpenSearch-Search-Pipeline `kvasir-rrf` mit dem
`score-ranker-processor` an und schreibt die drei Config-Werte hinein. `DocSearchService` schickt eine
`hybrid`-Query mit genau zwei Klauseln (BM25 und kNN) und hängt `search_pipeline=kvasir-rrf` als
Request-Parameter an — über Hibernate Searchs `requestTransformer`. Ohne diesen Parameter lieferte
OpenSearch die Teilscores unfusioniert zurück.

Voraussetzung ist das **neural-search-Plugin**; `hybrid`-Query und `score-ranker-processor` kommen von
dort. Fehlt es, scheitert der Start mit einer Meldung, die auf `GET /_cat/plugins` verweist.

**Konflikt mit Kernanforderung 5, bewusst so aufgelöst**: `hybrid`-Queries und Search-Pipelines sind
OpenSearch-spezifisch, Elasticsearch fusioniert über ein völlig anderes Konstrukt (`retriever`/`rrf`).
Backend-neutral bleiben Mapping, Filter und Projektionen; die Fusion selbst kann es nicht sein. Sie ist
deshalb auf `DocSearchService.hybridQuery(...)` eingegrenzt — ein Wechsel bedeutet eine zweite
Implementierung dieser einen Methode, keinen Umbau.

- Startpunkt sind gleiche Gewichte und `rank-constant=60`; eine Anpassung der Gewichte ist erst sinnvoll, wenn ein
  konkretes Evaluationsset zeigt, dass z. B. exakte Klassennamen/Fehlercodes (BM25-Stärke) von zu dominanten
  Vektor-Treffern verdrängt werden — nicht vorab spekulativ feintunen.

## Kernanforderung 5: Backend austauschbar (Elasticsearch ↔ OpenSearch)

Über eine einzige Konfigurationszeile umschaltbar, keine Codeänderung nötig:

```properties
# Standard: OpenSearch 3.8 auf der Kubernetes-Instanz
quarkus.hibernate-search-standalone.elasticsearch.version=opensearch:3.8
quarkus.hibernate-search-standalone.elasticsearch.hosts=localhost:9200
quarkus.hibernate-search-standalone.elasticsearch.protocol=http
# Alternative: Elasticsearch — einfach Version ohne "opensearch:"-Präfix setzen
# quarkus.hibernate-search-standalone.elasticsearch.version=8.15
```

Alle Mapping-Annotationen (`@FullTextField`, `@VectorField`, `@KeywordField`) und Such-Queries sind backend-neutral und
müssen bei einem Wechsel unverändert funktionieren.

**Transport**: Der Cluster ist auch über `https` erreichbar, sein Zertifikat stammt aber von einer
Development-CA, der die JVM nicht ab Werk vertraut (curl schon, weil es den System-CA-Store nutzt).
Solange diese CA nicht im JVM-Trust-Store liegt, läuft die Verbindung über `http`. Auth ist nicht
konfiguriert — der Cluster antwortet ohne Credentials.

**Adressen stehen nicht in diesem Repository.** `application.properties` trägt harmlose lokale
Vorgaben (`data`, `localhost:9200`); die tatsächlichen Endpunkte kommen aus `.env` bzw. im Cluster
aus der ConfigMap. In `.env` **müssen** sie `_DEV_`/`_PROD_`-präfigiert stehen: eine
Umgebungsvariable schlägt sonst auch `%test.docs.data-dir`, und die Testsuite liefe gegen die
echte Datenquelle statt gegen den Fixture-Baum (nachgemessen, siehe `.env.example`).

**Index-Name**: Der Cluster wird mit anderen Anwendungen geteilt, deshalb trägt der Index einen eigenen Präfix
(`kvasir-doc-chunk`) statt des aus dem Klassennamen abgeleiteten Defaults. Hibernate Search legt dahinter die
physische Index-Variante `kvasir-doc-chunk-000001` plus die Aliase `-read`/`-write` an.

**Index-Isolation für Tests**: Index- und Alias-Namen bekommen zusätzlich einen konfigurierbaren Präfix aus
`docs.index.prefix` (Default leer), umgesetzt über eine eigene `IndexLayoutStrategy`. Das Test-Profil setzt
`test-`, arbeitet damit auf einem eigenen Index und darf ihn folglich auch anlegen und wieder wegwerfen:

```properties
quarkus.hibernate-search-standalone.elasticsearch.layout.strategy=bean:prefixed-index-layout
docs.index.prefix=
%test.docs.index.prefix=test-
%test.quarkus.hibernate-search-standalone.schema-management.strategy=drop-and-create-and-drop
```

Ein Testlauf darf den produktiven Index **niemals** anfassen oder gar löschen — deshalb im Nicht-Test-Profil
`create-or-validate` statt einer Drop-Strategie.

## Kernanforderung 6: Embedding-Modell konfigurierbar austauschbar

> Ablauf, Klassenrollen und Stolpersteine der Umsetzung: [`docs/embedding.md`](docs/embedding.md)

**Default: `multilingual-e5-small`** (intfloat/multilingual-e5-small, 384 Dimensionen, 12 Layer) — mehrsprachig, damit
auch nicht-englische Doku-Anteile (z. B. deutsche Markdown-Dateien) sinnvoll eingebettet werden. Wichtig: Dieses Modell
ist **kein**
fertig verpacktes LangChain4j-Modul (anders als MiniLM oder das englischsprachige E5-Small- **v2**) — es läuft
ausschließlich über die generische `custom`/`OnnxEmbeddingModel`- Option und muss einmalig per `optimum-cli export onnx`
exportiert werden.

```properties
docs.embedding.model=custom
docs.embedding.onnx-path=models/multilingual-e5-small/model.onnx
docs.embedding.tokenizer-path=models/multilingual-e5-small/tokenizer.json
docs.embedding.dimension=384
docs.embedding.pooling-mode=MEAN
# Andere Optionen weiterhin verfügbar: minilm | minilm-quantized | bge-small | custom
```

**Kein eigener Export nötig**: `intfloat/multilingual-e5-small` liefert den ONNX-Export und den passenden
Tokenizer bereits im offiziellen Repository mit (`onnx/model.onnx`, `onnx/tokenizer.json`). Der ursprünglich
vorgesehene Weg über `optimum-cli export onnx` entfällt damit — es genügt, die beiden Dateien einmalig zu
laden:

```bash
scripts/download-embedding-model.sh
```

Die Dateien sind zusammen ~466 MB groß, liegen deshalb unter `models/` und sind **nicht** in Git — das Skript
ist idempotent und überspringt bereits vorhandene Dateien.

**E5-spezifische Besonderheit, die im Code berücksichtigt werden muss**: E5-Modelle erwarten Text mit vorangestelltem
Präfix — `"query: "` vor Suchanfragen,
`"passage: "` vor zu indexierenden Chunks. Ohne diese Präfixe fällt die Embedding-Qualität spürbar ab. Das gehört in den
Embedding-Aufruf selbst (z. B. einen dünnen Wrapper um das `EmbeddingModel`, der je nach Aufrufkontext — Ingestion vs.
Suche — automatisch das richtige Präfix voranstellt), nicht in die Chunking-/Parser-Logik.

Umgesetzt als `DocumentEmbedder` mit `embedQuery(...)` und `embedPassage(...)`/`embedPassages(...)` — es gibt
bewusst **keine** Methode, die unpräfigierten Text einbettet, damit ein Aufrufer das Präfix nicht vergessen
kann. Die Präfixe selbst sind konfigurierbar, weil sie modellspezifisch sind; für `minilm`/`bge-small` sind sie
Rauschen und gehören geleert:

```properties
docs.embedding.query-prefix=      # Default "query: "
docs.embedding.passage-prefix=    # Default "passage: "
```

**Fallstrick bei leeren Config-Werten**: SmallRye liest einen leeren Property-Wert als *fehlend* und scheitert
dann daran, ihn in einen `String` zu konvertieren. Properties, die leer sein dürfen (die beiden Präfixe und
`docs.index.prefix`), sind deshalb `Optional<String>` mit `orElse("")` — ein `String` mit
`defaultValue = ""` funktioniert nicht und lässt die Anwendung beim Start abbrechen.

CDI-Producer wählt zur Laufzeit die passende LangChain4j-`EmbeddingModel`-Implementierung
(`AllMiniLmL6V2EmbeddingModel`, `AllMiniLmL6V2QuantizedEmbeddingModel`,
`BgeSmallEnQuantizedEmbeddingModel`, oder generisches `OnnxEmbeddingModel` für eigene Modelle — Default-Fall für
`multilingual-e5-small`). **Wichtig**:
`docs.embedding.dimension` muss zum `@VectorField(dimension = ...)`
im Entity passen — bei Modellwechsel mit anderer Dimension ist ein vollständiger Reindex aller Projekte/Versionen nötig,
kein inkrementelles Update.

## MCP-Tools (Minimalspezifikation)

Die `description` eines Tools geht über das MCP-Protokoll an den Agenten und ist deshalb — anders als der Rest dieser
Datei — **auf Englisch** zu formulieren. Die folgenden Texte sind der verbindliche Wortlaut für
`@Tool(description = ...)`:

| Tool               | `description` (Wortlaut, Englisch)                                                                                                                                                             | Parameter                                                                                                         |
|--------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| `search_docs`      | Hybrid search (BM25 + kNN) across the Markdown, TXT, HTML, Javadoc, package doc and Java source of one project. Results are always restricted to the given project and version; cross-version hits have version = null. | `project`, `version`, `query`, `topK` (default 5), `type` (optional: markdown/txt/html/javadoc/package-doc/source) |
| `list_projects`    | List the indexed projects that are available. Source of truth for valid project values - never guess a project name. Returns all of them unless limit is given; the answer always reports the total and whether another page follows. | `offset` (optional, default 0), `limit` (optional, ohne Angabe alles, max 500) |
| `list_versions`    | List the indexed versions available for one project, oldest first. Source of truth for valid version values - never guess a version. Returns all of them unless limit is given; a project rarely has enough versions to be worth paging. Documentation that applies to every version is not a version and does not appear here; it shows up in search results with version = null. | `project`, `offset` (optional, default 0), `limit` (optional, ohne Angabe alles, max 500) |
| `get_class_source` | Return the complete source code of a class. Pinned to exactly the given project and version - it never falls back to another version, because source that merely looks plausible is worse than an error. Only classes that came from a sources archive have their source indexed. | `project`, `version`, `fullyQualifiedClassName` |

Dasselbe gilt für die `description` der einzelnen `@ToolArg`-Parameter sowie für Javadoc und Code-Kommentare: der
gesamte Code inklusive seiner Dokumentation ist englisch. Projektdokumentation für Menschen — `CLAUDE.md`,
`PLAN.md` und alles unter `docs/` — bleibt deutsch.

**Quelle der Werte: der Index, nicht das Dateisystem.** `list_projects` und `list_versions` werden über eine
Terms-Aggregation auf den Feldern `project` bzw. `version` beantwortet, `list_versions` zusätzlich gefiltert auf
das übergebene `project`. Das Verzeichnis `data/**` wird dafür *nicht* gelesen.

Begründung: Die Tools sind für den Agenten die Quelle der Wahrheit dafür, was er anschließend durchsuchen
kann. Ein Verzeichnis, das zwar existiert, aber noch nie indexiert wurde, ist für `search_docs` wertlos — es
aufzulisten würde den Agenten in eine Suche schicken, die garantiert leer ausgeht. Umgekehrt verschwindet ein
Projekt aus der Liste, sobald seine Chunks weg sind. Damit beschreibt die Antwort immer den tatsächlichen
Inhalt des Index.

Zwei Konsequenzen, die dabei beabsichtigt sind:

- Zwischen dem Ablegen von Dateien unter `data/**` und ihrem Auftauchen in `list_projects` liegt ein
  `POST /admin/index`. Das ist gewollt: vorher gibt es nichts zu suchen.
- Versionsübergreifende Chunks aus `<project>/doc/` haben kein `version`-Feld und bilden folglich keinen
  Aggregations-Bucket. `list_versions` liefert also echte Versionen und nie `null` — passend dazu, dass
  `null` keine Version ist, sondern „gilt für alle".

Bewusst **kein** MCP-Tool zum Auslösen der Indexierung — Indexierung ist ein administrativer Vorgang, kein
Retrieval-Schritt für den Agenten (siehe eigener REST-Abschnitt unten).

**Sortierregel für Auflistungen.** Wo `project` und `version` ausgegeben werden, gilt: nach `project`
aufsteigend, bei gleichem Projekt nach `version`. Zwei Gründe — eine feste Reihenfolge ist Voraussetzung
fürs Blättern (ohne sie könnte Seite 2 Einträge von Seite 1 wiederholen), und sie macht Antworten
reproduzierbar.

Für Versionen ist die alphabetische Sortierung dabei **falsch** und reicht nicht aus:

```
alphabetisch:  2.10.0  2.21.0  2.22.1  2.8.11  2.9.0   3.0.0  3.0.0-rc1
richtig:       2.8.11  2.9.0   2.10.0  2.21.0  2.22.1  3.0.0-rc1  3.0.0
```

`list_versions` sortiert deshalb **semantisch**: Segmentweise vergleichen, bei beidseitig numerischen
Segmenten numerisch, sonst zeichenweise; eine Version mit Vorabkennung (`-rc1`, `-SNAPSHOT`) steht vor
derselben Version ohne. Die Sortierung darf an Versionsstrings, die kein Semver sind (`8.15`,
`2.22.1.Final`), nicht scheitern, sondern muss auf den zeichenweisen Vergleich zurückfallen. Sie wird
**vor** dem Zuschnitt der Seite angewandt, sonst lägen die Seitengrenzen an der falschen Stelle.

**Ausdrücklich ausgenommen: `search_docs`.** Dessen Treffer sind nach Relevanz geordnet (RRF, siehe
Kernanforderung 4). Sie zusätzlich nach `project`/`version` zu sortieren würde genau das Ranking
zerstören, für das die Hybrid-Suche existiert.

**Auflistungen liefern eine Seite, aber Paginierung ist optional.** `list_projects` und `list_versions`
antworten nie mit einem nackten Array, sondern immer mit `items`, `offset`, `limit`, `total`, `hasMore` —
eine einheitliche Form, egal ob geblättert wird oder nicht. `total` und `hasMore` gehören zwingend dazu:
ohne sie kann ein Agent eine kurze vollständige Liste nicht von einer abgeschnittenen unterscheiden und
hört entweder zu früh auf oder fragt Seiten ab, die es nicht gibt.

**Ohne `limit` kommt alles.** Blättern muss möglich sein, aber nicht Pflicht — bei drei Versionen eines
Projekts wäre es reine Reibung. Fehlt `limit`, gibt es keine Obergrenze (nur die technische aus
`MAX_DISTINCT_VALUES`), und `hasMore` ist folgerichtig `false`. Ein `offset` wirkt auch ohne `limit`, damit
kein übergebenes Argument stillschweigend ignoriert wird. Wird `limit` angegeben, greift zusätzlich die
Obergrenze von 500 pro Aufruf.

Technische Grenze, die dabei zu kennen ist: Terms-Aggregationen lassen sich in Open-/Elasticsearch **nicht**
paginieren — es gibt keinen Offset dafür, das könnte nur eine Composite-Aggregation. Die verschiedenen Werte
werden deshalb vollständig ermittelt und die Seite daraus geschnitten. Für einen Katalog aus Projekten oder
Versionen (Dutzende bis Hunderte) ist das unproblematisch; jenseits von `MAX_DISTINCT_VALUES` wäre die
Auflistung unvollständig, was deshalb protokolliert und nicht als vollständig ausgegeben wird.

**Falsche Argumente sind Tool-Fehler, keine Protokollfehler.** Eine `IllegalArgumentException` aus der
Fachschicht wird über `@WrapBusinessError` in eine lesbare Fehlerantwort übersetzt
(`isError: true` samt Grund). Ohne das liefert die Extension ein nacktes `Internal error` auf
JSON-RPC-Ebene — daran kann ein Agent nichts korrigieren.

Jeder Treffer im `search_docs`-Ergebnis muss `project`, `version`, `source`,
`heading`/`signature` und den Text-Chunk enthalten — der Agent braucht die Herkunftsangabe, um Antworten korrekt zu
belegen.

## Indexierung als REST-Endpoint (kein MCP-Tool)

Das Auslösen des Scans von `data/` erfolgt über einen separaten **REST-Service** (JAX-RS/ Quarkus REST), nicht über ein
MCP-Tool. Begründung: Indexierung ist ein administrativer, potenziell lang laufender und schreibender Vorgang — der
MCP-Server soll für Agenten ein reines Retrieval-Interface bleiben, ohne dass ein Agent versehentlich (oder durch
manipulierte Eingaben im Kontext) einen Reindex anstößt.

```
POST /admin/index                          → vollständiger Scan von data/**
POST /admin/index?project=jackson          → nur dieses Projekt (alle Versionen)
POST /admin/index?project=jackson&version=2.18.2   → nur diese Projekt-Version
POST /admin/index?project=jackson&force=true        → Fingerabdrücke ignorieren, alles neu aufbauen
GET  /admin/index/status                   → Status ALLER Scans (laufend + zuletzt beendete)
GET  /admin/index/status?scan-id=<id>      → Status genau dieses einen Scans
```

- Läuft **asynchron**; jeder erfolgreiche `POST` gibt sofort eine
  `202 Accepted` mit einer neu erzeugten `scan-id` zurück — **mehrere Scans können parallel laufen** (z. B. gleichzeitig
  `project=jackson` und `project=jobrunr` angestoßen), daher muss der Status mehrere Scans gleichzeitig nachhalten
  können, nicht nur "den letzten"
- **Überlappende Scans werden abgelehnt.** Parallel laufen dürfen nur Scans, die sich nicht in die Quere kommen
  können. Ein Scan blockiert genau die Anfragen, deren Geltungsbereich sich mit seinem schneidet:

  | läuft gerade | blockiert | erlaubt |
  |---|---|---|
  | Vollscan | alles | – |
  | `project=X` | Vollscan, `project=X`, `project=X&version=*` | `project=Y` |
  | `project=X&version=V` | Vollscan, `project=X`, `project=X&version=V` | `project=X&version=W`, `project=Y` |

  Zwei Versionen desselben Projekts dürfen bewusst nebeneinander laufen: sie schreiben disjunkte Chunks und
  disjunkte `IndexedFile`-Einträge, eine Sperre wäre reine Schikane.

  Eine abgelehnte Anfrage bekommt **`409 Conflict`** mit dem laufenden Scan im Rumpf — Fehler *und* Verweis in
  einem, damit der Aufrufer dessen Status weiterverfolgen kann statt zu raten:

  ```json
  {
    "message": "a scan covering the whole data directory is already running; follow it under /admin/index/status?scan-id=8773...",
    "runningScan": { "scanId": "8773...", "status": "RUNNING", "scannedFiles": 1, "...": "..." }
  }
  ```

  Prüfung und Registrierung laufen unter einer Sperre, sonst kämen zwei gleichzeitig eintreffende Anfragen
  beide durch. Abgelehnt wird sofort, es wird **nicht** in eine Warteschlange gestellt: ein Scan ist idempotent,
  ein Nachholen also unnötig — der Aufrufer kann nach Abschluss einfach erneut anstoßen
- **`GET /admin/index/status` ohne Parameter**: Liste aller bekannten Scans (laufend und historisch, ggf. mit
  Limit/Retention), jeweils mit `scanId`, `status`
  (`RUNNING`/`COMPLETED`/`FAILED`), `project`/`version` (oder `null` bei Vollscan),
  `startedAt`, sowie den Zählern aus dem nächsten Punkt
- **`GET /admin/index/status?scan-id=<id>`**: Detailstatus genau dieses Scans, inkl. Zähler (Anzahl gescannter/neu
  indexierter/übersprungener/fehlerhafter Dateien) und bei
  `FAILED` einer Fehlermeldung; `404`, falls die `scan-id` unbekannt ist
- Scan-Status wird in-memory verwaltet (`ConcurrentHashMap<String, ScanStatus>`) — Verlust bei Neustart ist für Phase 1
  akzeptabel, da Reindexierung idempotent ist (Hash-Skip)
- Nutzt intern denselben Scanner/dieselbe Parser-Pipeline wie in Kernanforderung 0 beschrieben — nur der Aufrufweg
  ändert sich, nicht die Ingestion-Logik
- **Kein Auth im ersten Wurf** (konsistent mit Nicht-Zielen), aber der Endpoint sollte nicht auf demselben Port wie der
  öffentliche MCP-HTTP-Transport liegen, oder zumindest über einen eigenen Pfad-Präfix von MCP-Clients klar getrennt
  sein — Grundlage, um später gezielt nur diesen Pfad abzusichern (Basic Auth/API-Key), ohne den MCP-Zugriff selbst zu
  berühren.
  **Umgesetzt als Pfad-Präfix**: `/admin/**` neben `/mcp` auf demselben Port. Quarkus 3.33 bietet keinen
  Weg, JAX-RS-Ressourcen auf dem Management-Port zu betreiben; der eigene Port bliebe also nur über eine
  zweite Anwendung erreichbar. Absicherbar wird das später über
  `quarkus.http.auth.permission."admin".paths=/admin/*`, ohne den MCP-Zugriff zu berühren.

**Zum Ausprobieren von Hand**: `httpclient/` enthält Dateien für den IntelliJ-HTTP-Client —
`admin-index.http` für die Endpunkte oben, `mcp.http` für die vier MCP-Tools inklusive
Session-Handshake. Die Umgebung (`dev`/`cluster`) kommt aus `http-client.env.json`.

**Aggregierbarkeit der Filter-Felder**: `project`, `version` und `type` sind mit
`@KeywordField(aggregable = Aggregable.YES)` gemappt. Ohne das legt Hibernate Search Keyword-Felder mit
`doc_values: false` an, und eine Terms-Aggregation („welche Projekte gibt es?") ist schlicht nicht möglich.
`list_projects`/`list_versions` werden darüber beantwortet (siehe Abschnitt „MCP-Tools"). Aggregierbarkeit ist eine Mapping-Eigenschaft: sie später nachzurüsten erzwingt einen vollständigen
Reindex, deshalb tragen die drei Pflicht-Filter sie von Anfang an. Die übrigen Keyword-Felder (`source`,
`since`, `fullyQualifiedClassName`) bleiben bewusst ohne — sie werden gefiltert, nicht aggregiert.

## Wiederherstellung und Strukturänderungen

> Ablauf, Snapshot-Prozedur und die Liste, welche Änderung einen Reindex erzwingt:
> [`docs/recovery.md`](docs/recovery.md)

**Die beiden Indizes gehören zusammen.** `kvasir-doc-chunk` und `kvasir-indexed-file` werden gemeinsam
geschrieben und müssen gemeinsam gesichert und zurückgespielt werden. Wird nur der Chunk-Index
wiederhergestellt, baut der nächste Scan alles neu auf — langsam, aber harmlos. Wird nur der
Fingerabdruck-Index wiederhergestellt, überspringt der Scan **jede** Datei, der Chunk-Index bleibt leer und
nichts meldet es. Deshalb prüft `IndexConsistencyCheck` beim Start auf Projekte, die Fingerabdrücke, aber
keine Chunks haben, und nennt Ursache und Ausweg. Er repariert bewusst nichts — Fingerabdrücke auf Verdacht
beim Start zu löschen wäre eine destruktive Vermutung.

**`force=true`** am Admin-Endpoint ignoriert die Fingerabdrücke und baut den gewählten Bereich neu auf. Auf
einem erzwungenen Lauf wird nicht aufgeräumt, weil die Fingerabdrücke dabei ja nichts über den Bestand
aussagen.

## Architektur

```
data/<project>/doc/**            (version = null, projektweit)
data/<project>/<version>/**      (version-spezifisch)
  ├─ *.md   → flexmark → Chunking nach Überschrift
  ├─ *.txt  → Wortfenster-Chunking (~300 Wörter)
  ├─ *.html → Jsoup → Chunking nach h1–h3
  └─ *-sources.jar → entpacken → *.java via JavaParser
        ├─ Klassen/Methoden-Javadoc  → type = "javadoc"
        └─ package-info.java/.html   → type = "package-doc"
        │
        ▼
  DocChunk-Entity (project, version | null, type, source, heading, text)
        │
        ├─ LangChain4j EmbeddingModel → embedding: float[]
        │
        ▼
  Hibernate Search Standalone (@Indexed Entity)
        │  @FullTextField text  →  BM25
        │  @VectorField embedding → kNN
        │  @KeywordField project, version, type → Pflicht-Filter
        │
        ▼
  Backend: OpenSearch (Standard) — per Config auf Elasticsearch umschaltbar
        │
        ▼
  Quarkus-MCP-Tools (@Tool-annotierte CDI-Beans)
```

## Nicht-Ziele (bewusst weglassen)

- Kein LLM-Aufruf im Server selbst — nur Retrieval, keine Antwortgenerierung
- Kein automatisches Crawlen beliebiger externer URLs — Quellen werden explizit lokal/aus Repo übergeben
  (Angriffsfläche/Security)
- Keine Auth im ersten Wurf — für lokale/interne Nutzung; OAuth 2.1 erst bei Remote-Multi-User-Bedarf nachrüsten
- Kein Artifactory-/Wiki-/S3-Connector im Scanner selbst — `docs.data-dir` bleibt reine Datei-Pfad-Konfiguration,
  quellsystemspezifisches Ziehen ist ein späterer, eigenständiger Baustein (siehe Kernanforderung 0)
- Keine zentrale Rohdaten-Datenbank (z. B. PostgreSQL) — Dateisystem/S3 statt DB-Blobs
- Kein manuelles Score-Mixing auf Rohwert-Ebene (BM25-Rohscore + Cosine-Rohscore per eigener Formel
  verrechnen/normalisieren) — die Fusion läuft ausschließlich über OpenSearchs RRF-Pipeline (Kernanforderung 4). Erlaubt
  und vorgesehen ist ausschließlich das Setzen der dortigen Pipeline-Parameter (`rank-constant`, `weight-bm25`,
  `weight-vector`) über Config-Properties

Akzeptanzkriterien und der Umsetzungsplan (Reihenfolge, Git-Workflow, Task-Zuschnitt)
stehen bewusst nicht hier, sondern in `PLAN.md` — diese Datei ist die technische Spezifikation ("was"), `PLAN.md` das
Vorgehen ("wie/wann geprüft").


### Bekannte Grenze der Ingestion

Ein Archiv wird als Ganzes geparst: alle Chunks liegen in einer Liste, jeder mit seinem 384-Float-Vektor.
Bei `jackson-core` sind das 2069 Chunks, bei einem sehr großen Projekt eher 50.000 — inklusive der
`source`-Chunks, die je eine komplette Quelldatei als String halten. Das sind dann mehrere hundert MB Heap.
Sauber wäre, eintragsweise zu streamen und in Blöcken zu schreiben; das bricht allerdings die heutige
Zusicherung von `ChunkIngestor.replace(...)`, die alten Chunks einer Datei genau einmal und vollständig zu
ersetzen. Bewusst offen gelassen, bis ein Projekt dieser Größe tatsächlich ansteht.
