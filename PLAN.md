# Umsetzungsplan: Kvasir

Diese Datei regelt **wie** vorgegangen wird (Git-Workflow, Reihenfolge, Review-Gates,
Akzeptanzkriterien pro Schritt). Die technische Spezifikation ("was") steht in
`CLAUDE.md` — vor jedem Task dort den relevanten Abschnitt lesen.

## Git-Workflow (für jeden Task verbindlich)

1. Vor Start eines Tasks prüfen:
   - `git branch --show-current` → muss `main` sein, sonst **abbrechen** und den
     Benutzer bitten, die aktuellen Änderungen in `main` zu mergen und auf `main` zu
     wechseln
   - `git status --porcelain` → muss leer sein, sonst **abbrechen** und den Benutzer
     bitten, offene Änderungen zu committen oder zu verwerfen
2. Für den Task einen eigenen Branch erstellen: `git checkout -b feature/<task-slug>`
3. Während der Arbeit am Task **zwischendurch committen**, nicht nur ein
   Abschluss-Commit — kleine, nachvollziehbare Schritte
4. Ist der Task fertig: **Stopp.** Dem Benutzer eine kurze Zusammenfassung geben (was
   wurde umgesetzt, welche Akzeptanzkriterien sind erfüllt, wie manuell/automatisiert
   getestet wurde) und um Review bitten
5. **Niemals selbst nach `main` mergen** — das macht ausschließlich der Benutzer nach
   erfolgtem Review
6. Erst nach explizitem Go des Benutzers mit dem nächsten Task fortfahren (wieder ab
   Schritt 1, also erneut prüfen, dass zwischenzeitlich nach `main` gemerged wurde)

## Task-Liste

### Task 1 — Projekt-Grundgerüst

Quarkus-Projekt mit aktueller Quarkus-LTS und den aktuellsten stabilen Versionen aller
benötigten Libraries anlegen (siehe `CLAUDE.md`, Tech-Stack und Setup-Schritt 1). Java 25
als Mindestversion setzen. Noch keine Extensions über das Grundgerüst hinaus, keine
Fachlogik.

**Akzeptanzkriterien**
- [x] Projekt baut (`mvn package`) und startet (`quarkus dev`) ohne Fehler
- [x] `maven.compiler.release` ist auf `25` gesetzt
- [x] Keine MCP-, Such- oder Embedding-Abhängigkeiten sind bereits eingebunden — dieser
      Task liefert ausschließlich das leere Grundgerüst

*Erledigt in `c0239e8` (Quarkus LTS 3.33.3.1, Java 25).*

### Task 2 — MCP-Server-Fragment (nur Signaturen, keine Logik)

`quarkus-mcp-server-http` einbinden. Alle vier MCP-Tools als `@Tool`-annotierte Methoden
anlegen, mit korrekten Signaturen und Beschreibungen laut `CLAUDE.md`, aber **ohne**
fachliche Implementierung (Platzhalter-Rückgabe, z. B. leere Liste / "not implemented").

Die Tool-Beschreibungen gehen an den Agenten und sind daher englisch — verbindlicher Wortlaut
steht in `CLAUDE.md`, Abschnitt „MCP-Tools".

| Tool | Beschreibung (Kurzfassung) | Parameter |
|---|---|---|
| `list_projects` | Verfügbare indexierte Projekte auflisten | – |
| `list_versions` | Verfügbare indexierte Versionen **eines Projekts** auflisten | `project` |
| `get_class_source` | Vollständigen Quellcode einer Klasse liefern | `project`, `version`, `fullyQualifiedClassName` |
| `search_docs` | Hybrid-Suche (BM25 + kNN) über Markdown/TXT/HTML/Javadoc/Package-Doc/Source | `project`, `version`, `query`, `topK`, `type` (optional) |

**Wichtig, da im ursprünglichen Plan vertauscht**: `list_versions` listet Versionen
*innerhalb* eines Projekts (Parameter `project` ist Pflicht) — nicht dieselbe
Beschreibung wie `list_projects`.

**Sprache im Code**: Javadoc, Code-Kommentare, `@Tool`-/`@ToolArg`-Beschreibungen und
Assertion-Messages sind englisch. Projektdokumentation (`CLAUDE.md`, `PLAN.md`, `docs/`)
bleibt deutsch.

**Akzeptanzkriterien**
- [x] Alle vier Tools sind über den MCP Inspector sichtbar (`tools/list`)
- [x] Jedes Tool ist einzeln aufrufbar und liefert eine wohlgeformte (wenn auch leere/
      Platzhalter-) Antwort, keine Exceptions
- [x] `list_versions` verlangt `project` als Pflichtparameter, `list_projects` hat keine
      Parameter

*Geprüft mit `@modelcontextprotocol/inspector --cli` gegen den laufenden Server und
dauerhaft abgesichert durch `DocsMcpToolsTest` (`mvn test`).*

### Task 3 — OpenSearch-Anbindung und Datenmodell

Hibernate Search Standalone einbinden, gegen die vorhandene OpenSearch-Instanz konfigurieren
(siehe `CLAUDE.md`, Kernanforderung 5). `DocChunk`-Entity mit `@Indexed`,
`@FullTextField`, `@VectorField`, `@KeywordField` anlegen. Noch keine Ingestion, nur
Schema + Verbindungstest (z. B. ein manuell eingefügtes Test-Dokument lässt sich
wiederfinden).

**Akzeptanzkriterien**
- [x] Anwendung verbindet sich beim Start erfolgreich mit der vorhandenen OpenSearch-Instanz
- [x] `DocChunk`-Index wird beim Start angelegt (Feldtypen für `project`/`version`/`type`
      als Keyword, `text` als Volltext, `embedding` als Vektor mit korrekter Dimension)
- [x] Ein manuell persistiertes Test-`DocChunk` ist über eine einfache Testabfrage wieder
      auffindbar

*Geprüft gegen die reale Instanz (OpenSearch 3.8.0) durch
`DocChunkSchemaTest` (liest das Mapping aus dem Cluster) und `DocChunkIndexTest`
(indexiert einen Chunk, findet ihn wieder, räumt ihn weg).*

### Task 4 — Embedding-Modell-Integration

`quarkus-langchain4j` einbinden, `EmbeddingModel` konfigurierbar per
`docs.embedding.model` (siehe `CLAUDE.md`, Kernanforderung 6) bereitstellen. **Default:
`multilingual-e5-small`**, per `optimum-cli export onnx` exportiert (Modell ist nicht
als fertiges LangChain4j-Maven-Modul verfügbar, läuft über die generische
`custom`/`OnnxEmbeddingModel`-Option). Query-/Passage-Präfixe (`"query: "`/`"passage: "`)
im Aufruf-Wrapper berücksichtigen, nicht in Parsern. Test: Text rein, Vektor mit
erwarteter Dimension (384) raus.

**Akzeptanzkriterien**
- [x] Embedding-Modell ist über `docs.embedding.model` austauschbar (`minilm`,
      `minilm-quantized`, `bge-small`, `custom`), ohne Code anzufassen
- [x] Default-Konfiguration nutzt `multilingual-e5-small` über die `custom`-Option
- [x] Such-Query wird intern mit `"query: "`, jeder zu indexierende Chunk mit
      `"passage: "` präfixiert, bevor er ans Embedding-Modell geht
- [x] `docs.embedding.dimension` und tatsächliche Modell-Ausgabedimension werden beim
      Start abgeglichen; Abweichung führt zu einem klaren Fehler beim Start, nicht zu
      stillen Falschtreffern

*Der `optimum-cli`-Export entfällt: der ONNX-Export liegt fertig im offiziellen
Modell-Repository, `scripts/download-embedding-model.sh` lädt ihn.*

### Task 5 — Parser (Markdown, TXT, HTML, Java-Source inkl. Package-Javadoc)

Vier Parser gemäß `CLAUDE.md` Kernanforderung 0 und 2 implementieren, jeweils mit
Unit-Tests gegen kleine Beispieldateien (im Projekt mitgeliefert, kein externes
Fremdprojekt). Ausgabe: `DocChunk`-Objekte, noch ohne Persistierung/Embedding.

**Akzeptanzkriterien**
- [x] Markdown wird nach Überschrift gechunkt, TXT in Wortfenstern (~300 Wörter), HTML
      nach `<h1>`–`<h3>`
- [x] Java-Source-Parser erzeugt pro öffentlicher Klasse/Methode einen Chunk mit
      `fullyQualifiedClassName`-Bezug
- [x] Package-Javadoc aus `package-info.java` wird als eigener Chunk mit
      `type = "package-doc"` erzeugt, obwohl er zu keiner Klasse gehört
- [x] Alle vier Parser haben Unit-Tests mit mitgelieferten Beispieldateien

*32 Tests gegen Beispieldateien unter `src/test/resources/samples`. Zusätzlich zu den
Kriterien erzeugt der Java-Parser einen `source`-Chunk mit dem vollständigen Quelltext —
sonst hätte `get_class_source` in Task 9 keine Datenquelle (siehe `CLAUDE.md`,
Kernanforderung 2).*

### Task 6 — `data`-Scanner und REST-Admin-Endpoint

Scanner implementieren, der `docs.data-dir` rekursiv nach dem Layout aus `CLAUDE.md`
Kernanforderung 0 durchläuft, JARs entpackt, die Parser aus Task 5 aufruft, Embeddings
aus Task 4 erzeugt und über Hibernate Search (Task 3) persistiert. REST-Endpunkte
`POST /admin/index` (optional `project`/`version`) und `GET /admin/index/status`
(optional `scan-id`) gemäß `CLAUDE.md`.

**Akzeptanzkriterien**
- [x] `POST /admin/index` indexiert den kompletten `data`-Baum asynchron und liefert
      sofort `202` mit `scan-id`
- [x] `POST /admin/index?project=X&version=Y` indexiert nachweislich nur diese
      Projekt-Version, nicht den gesamten Baum
- [x] `GET /admin/index/status` ohne Parameter liefert alle Scans (auch mehrere parallel
      laufende); mit `scan-id` liefert er ausschließlich diesen einen Scan, `404` bei
      unbekannter ID
- [x] Erneuter Scan ohne Dateiänderungen erzeugt keine doppelten Chunks und embedded
      keine bereits indexierten Dateien neu (Pfad+Hash-Skip)
- [x] Ein Dokument unter `<project>/doc/` ist danach mit `version: null` im Index
      auffindbar
- [x] `docs.data-dir` ist konfigurierbar; kein fest codierter Pfad im Code
- [x] `POST /admin/index` ist über **kein** MCP-Tool erreichbar

**Vor Review**: mit dem Jackson-Beispiel aus `data/jackson/2.22.1/` (Sources-JAR +
mind. eine Markdown-Datei versionsspezifisch + eine unter `data/jackson/doc/`) einmal
real indexieren und Ergebnis im OpenSearch-Index stichprobenartig prüfen.

*Real durchgeführt: 3 Dateien, 2069 Chunks (1929 javadoc, 124 source, 10 package-doc,
6 markdown). Zweiter Scan 38 ms statt 18 s, 3 übersprungen, Dokumentzahl unverändert.
Automatisiert abgesichert durch `DataScannerTest` und `IndexAdminResourceTest`.*

### Task 7 — `list_projects` implementieren

Fachlogik hinter dem in Task 2 angelegten Platzhalter.

**Festgelegt**: Die Werte werden **aus dem Index** ermittelt (Terms-Aggregation auf
`project`), nicht aus dem Dateiverzeichnis `data/*`. Begründung und Konsequenzen stehen
in `CLAUDE.md`, Abschnitt „MCP-Tools". Dasselbe gilt für Task 8. Die dafür nötige
Aggregierbarkeit der Felder ist seit Task 6 im Mapping vorhanden.

**Akzeptanzkriterien**
- [x] Liefert nach Indexierung von ≥ 2 Projekten (aus Task 6) genau diese, keine
      Duplikate, keine internen IDs/Pfade in der Antwort
- [x] Ein Projektverzeichnis unter `data/`, das noch nicht indexiert wurde, taucht
      **nicht** auf

- [x] Ergebnis ist seitenweise abrufbar (`offset`/`limit`) und meldet `total` sowie
      `hasMore`; ohne `limit` kommt die vollständige Liste

*Real geprüft: vor dem Scan lieferte `list_projects` nur `jackson`, obwohl `jobrunr`
bereits unter `data/` lag; nach `POST /admin/index` beide, alphabetisch. Umgesetzt in
`IndexCatalog.projects(offset, limit)` über eine Terms-Aggregation auf `project`.*

### Task 8 — `list_versions` implementieren

**Festgelegt**: ebenfalls aus dem Index (Terms-Aggregation auf `version`, gefiltert auf
`project`), siehe Task 7.

**Akzeptanzkriterien**
- [x] Liefert für ein Projekt mit ≥ 2 indexierten Versionen genau diese Versionen
- [x] Unbekanntes `project` liefert eine leere Liste, keine Exception
- [x] Versionsübergreifende Chunks aus `<project>/doc/` erzeugen keinen `null`-Eintrag
      in der Versionsliste
- [x] Versionen sind **semantisch** sortiert, nicht alphabetisch: `2.9.0` vor `2.10.0`,
      `3.0.0-rc1` vor `3.0.0` (siehe `CLAUDE.md`, Sortierregel für Auflistungen)
- [x] Ein Versionsstring, der kein Semver ist (`8.15`, `2.22.1.Final`), lässt die
      Sortierung nicht scheitern
- [x] Ergebnis ist wie `list_projects` seitenweise abrufbar; die Sortierung wirkt **vor**
      dem Zuschnitt der Seite. Ohne `limit` kommt die vollständige Versionsliste — der
      Regelfall, weil ein Projekt selten genug Versionen für eine Paginierung hat

*Umgesetzt in `IndexCatalog.versions(project, offset, limit)` mit `VersionComparator`.
Real geprüft: `list_versions(jackson)` liefert `["2.21.0","2.22.1"]`, ein unbekanntes
Projekt eine leere Seite ohne Fehler, ein leerer `project`-Wert eine lesbare
Fehlermeldung. Die Test-Fixture enthält `1.0.0`, `1.9.0` und `1.10.0`, weil genau
dort alphabetische und semantische Sortierung auseinanderlaufen.*

### Task 9 — `get_class_source` implementieren

**Akzeptanzkriterien**
- [x] Liefert für eine bekannte Klasse aus dem indexierten Jackson-Testprojekt den
      vollständigen Quellcode
- [x] Falsches `project`/`version`/Klassenname liefert einen klaren Fehler, keinen
      Treffer aus einer anderen Version

*Real geprüft: `com.fasterxml.jackson.core.JsonFactory` aus `jackson`/`2.22.1` kommt mit
2358 Zeilen zurück. Dieselbe Klasse mit `version=2.21.0` liefert einen Fehler, der die
Version nennt, in der sie tatsächlich liegt — statt still den Quelltext der anderen
Version. Umgesetzt in `ClassSourceLookup`.*

### Task 10 — `search_docs` implementieren

Hybrid-Suche inkl. RRF-Fusion (Kernanforderung 4), AND-Pflicht bei kurzen Anfragen
(Kernanforderung 3), Filter auf `project` + (`version` ODER `version IS NULL`)
(Kernanforderung 1).

**Akzeptanzkriterien**
- [x] Treffer enthalten `project`, `version` (oder `null`), `source`,
      `heading`/`signature`, Text-Chunk
- [x] Filter auf `project`+`version` liefert **keine** Treffer aus anderen
      Projekten/Versionen
- [x] Eine Suche mit ≤ `docs.search.exact-match-max-terms` Begriffen liefert im
      BM25-Teilscore nur Treffer, die alle Begriffe enthalten
- [x] `docs.search.rrf.rank-constant` und `docs.search.rrf.weight-*` sind über Config
      änderbar und wirken über die OpenSearch-Pipeline — keine eigene Formel im Code,
      die BM25-/Cosine-Rohwerte verrechnet

*Real geprüft: Start mit `-Ddocs.search.rrf.rank-constant=17 -Ddocs.search.rrf.weight-bm25=0.8`
erzeugt im Cluster genau diese Pipeline-Definition. Die AND-Pflicht wurde am BM25-Teil
gegengeprüft — dieselbe Anfrage liefert mit `100%` sechs Treffer (alle mit allen drei
Begriffen) und mit `75%` achtundzwanzig. Grep über `src/main/java` findet keine
Score-Arithmetik.*

### Task 11 — Wiederherstellung, Delta-Reindexierung und Änderungen an der Indexstruktur

Bis hierher wurde ausschließlich der Gutfall betrachtet. Ein Index kann aber beschädigt
werden, verloren gehen oder durch neue Features eine geänderte Struktur brauchen. Für
Open-/Elasticsearch ist der Standardweg dafür Snapshot & Restore; danach soll ein
Reindexlauf nur noch nachziehen, was sich seit dem Snapshot geändert hat.

**Ausgangslage (im Code geprüft, nicht vermutet)**

- Es gibt **zwei** Indizes, die zusammengehören: `kvasir-doc-chunk` (die Chunks) und
  `kvasir-indexed-file` (Pfad + SHA-256 je Datei). Der zweite ist die Grundlage des
  Hash-Sprungs und damit bereits der Mechanismus für „nur Geändertes neu indexieren" —
  er muss dafür lediglich zum Chunk-Index passen.
- **Der gefährliche Fall ist die halbe Wiederherstellung.** Wird nur `doc-chunk`
  zurückgespielt, ist bloß der nächste Lauf langsam (alle Hashes fehlen, alles wird neu
  gemacht). Wird nur `indexed-file` zurückgespielt, überspringt der Scanner **alles** und
  der Chunk-Index bleibt leer — ohne Fehlermeldung. Genau diese Falle ist beim Einbau von
  `aggregable = Aggregable.YES` real aufgetreten: nach dem Löschen von `doc-chunk` musste
  auch `indexed-file` gelöscht werden, sonst wäre der neue Index leer geblieben.
- **Gelöschte Dateien hinterlassen heute Waisen.** Der einzige Löschpfad ist
  `ChunkIngestor.deletePreviousChunks(...)`, und der läuft nur für Dateien, die der Scanner
  tatsächlich verarbeitet. Verschwindet eine Datei aus `data/`, bleiben ihre Chunks
  dauerhaft im Index. Das ist unabhängig von Snapshots ein Fehler, verschärft sich nach
  einem Restore aber.
- **Strukturänderungen werden bereits erkannt, aber nur als harter Abbruch.**
  `schema-management.strategy=create-or-validate` verweigert den Start und nennt die
  betroffenen Felder (real gesehen: `project`, `type`, `version`). Es gibt keinen Hinweis,
  was zu tun ist, und keinen Weg außer manuellem Löschen beider Indizes.
- **Die Alias-Struktur für einen Blue/Green-Reindex existiert schon**: die
  `PrefixedIndexLayoutStrategy` legt `…-000001` mit den Aliassen `-read` und `-write` an —
  das ist genau die Grundlage, um in einen neuen Index zu schreiben und am Ende die Aliasse
  umzuhängen.
- Auf dem Cluster ist derzeit **kein Snapshot-Repository** konfiguriert (`GET /_snapshot`
  liefert `{}`).

**Zu untersuchen (Ergebnis gehört als Entscheidung nach `CLAUDE.md`)**

- Muss eine **Schema-Version** mitgeführt werden? Naheliegender Kandidat: die Version im
  `IndexedFile` ablegen. Ändert sich die Indexstruktur, passen die Hashes nicht mehr und
  alle Dateien gelten automatisch als geändert — der Reindex ergäbe sich von selbst, ohne
  dass jemand daran denken muss, den Hash-Index mitzulöschen.
- Welche Änderungen sind **additiv** (neues Feld, das alte Dokumente einfach nicht haben)
  und welche erzwingen einen vollständigen Reindex (andere Embedding-Dimension, geänderte
  Aggregierbarkeit, anderer Analyzer)? Ergebnis als Liste in `CLAUDE.md`, damit man es vor
  einer Änderung nachschlagen kann statt es beim Start zu erfahren.
- Trägt ein **Blue/Green-Reindex über die Aliasse** hier — in `…-000002` schreiben, dann
  `-read` umhängen — und unterstützt Hibernate Search das ausreichend? Falls ja, wäre ein
  Strukturwechsel ohne Suchausfall möglich.
- Wie erkennt man einen **beschädigten Index**, bevor ein Agent falsche Antworten bekommt?
  Kandidat: Abgleich der Anzahl `IndexedFile`-Einträge gegen die Anzahl verschiedener
  `source`-Werte in `doc-chunk`.

**Akzeptanzkriterien**

- [ ] Snapshot-Repository ist eingerichtet und dokumentiert; ein Snapshot **beider**
      Indizes lässt sich erzeugen und zurückspielen
      → **blockiert**: `path.repo` ist auf dem Cluster leer, ein `fs`-Repository wird
      abgelehnt. Das ist eine Cluster-Einstellung (`opensearch.yml`), keine der Anwendung.
      Prozedur ist in `docs/recovery.md` dokumentiert, aber nicht durchgeführt.
- [x] Nach einem Restore indexiert `POST /admin/index` nachweislich nur die seit dem
      Snapshot geänderten Dateien — belegt durch die Zähler `indexedFiles`/`skippedFiles`
- [x] Eine Wiederherstellung, bei der `doc-chunk` und `indexed-file` **nicht** zueinander
      passen, wird erkannt und gemeldet, statt stillschweigend einen leeren oder
      unvollständigen Index zu hinterlassen
- [x] Eine Datei, die aus `data/` entfernt wurde, hinterlässt nach dem nächsten Scan keine
      Chunks mehr im Index
- [x] Eine Änderung der Indexstruktur führt zu einer Meldung, die sagt **was zu tun ist**,
      nicht nur dass die Validierung fehlgeschlagen ist
- [x] Der Ablauf „Index kaputt → Snapshot zurückspielen → nachindexieren" ist in `docs/`
      als nachvollziehbare Anleitung festgehalten

*Untersuchungsergebnisse: Die Schema-Version wird als `IndexSchema.VERSION` im Fingerabdruck
mitgeführt — Hochzählen erzwingt den Reindex von selbst. Die Liste, welche Änderung einen
Reindex erzwingt und welche nicht, steht in `docs/recovery.md`. Der Blue/Green-Reindex über
die vorhandenen Aliasse ist als Möglichkeit vermerkt, aber nicht untersucht.*

**Nicht Teil dieses Tasks**: automatisches Anlegen von Snapshots nach Zeitplan und
Snapshot-Verwaltung über den Admin-Endpoint — das ist Betrieb, nicht Anwendungslogik.

### Task 12 — S3 als Datenquelle für `data/`

`docs.data-dir` soll auf einen S3-Bucket zeigen können, nicht nur auf ein lokales
Verzeichnis. Auf der k3s-Instanz steht bereits ein S3-Store bereit.

**Ausgangslage (geprüft, nicht vermutet)**

- **Der Code ist vorbereitet.** `DataStore` erkennt eine URI mit Schema und übergibt sie an
  `FileSystems.newFileSystem(URI, Map)`; fehlt der Provider, bricht der Start mit einer
  Meldung ab, die Schema und Property nennt. Es fehlt also **nur der Provider im
  Classpath**, keine Codeänderung an Scanner oder Parsern.
- **Kandidat**: `software.amazon.nio.s3:aws-java-nio-spi-for-s3` 2.5.0 — von AWS, zuletzt
  Juni 2026 aktualisiert. Alternative: `org.carlspring.cloud.aws:s3fs-nio` 3.0.0.
  `com.upplication:s3fs` ist seit 2018 tot und scheidet aus.
- **Das Staging von Archiven wird tragend.** `ZipArchiveOpener.requiresRandomAccess()` ist
  `true`, der Zip-Provider muss also springen. `ArchiveReader` kopiert ein Archiv, das nicht
  auf dem Default-Dateisystem liegt, deshalb einmal sequentiell auf lokale Platte. Genau
  dafür wurde das gebaut — bisher aber nur gegen Jimfs bewiesen, und Jimfs ist ein
  vollwertiges Dateisystem, S3 nicht.

**Das Hauptrisiko: S3 kennt keine Verzeichnisse**

`DataScanner` erkennt Projekte und Versionen daran, dass er **Verzeichnisse** auflistet:

```java
Files.list(parent).filter(Files::isDirectory)
```

In S3 gibt es keine Verzeichnisse, nur Schlüssel mit Präfixen. Ob `Files.isDirectory` für
`bucket/jackson/2.22.1/` `true` liefert, hängt vollständig davon ab, wie der Provider das
emuliert — und ob dafür Marker-Objekte nötig sind. **Das ist zu verifizieren, bevor
irgendetwas anderes getestet wird**; scheitert es, ist die Layout-Erkennung neu zu denken.

**Zu untersuchen**

- Erkennt der Provider Präfixe als Verzeichnisse, ohne dass leere Marker-Objekte angelegt
  werden müssen?
- Endpoint-Override und Path-Style-Zugriff für einen selbst gehosteten S3 (MinIO o. ä.):
  Wie werden sie gesetzt, und reicht die Standard-Credential-Kette der AWS-SDK, damit ein
  Kubernetes-Secret als Umgebungsvariablen genügt?
- **Kostenfrage Hash**: `ChunkIngestor.hash(Path)` liest die ganze Datei. Über S3 heißt das,
  dass eine unveränderte 50-MB-JAR bei **jedem** Scan vollständig heruntergeladen wird, nur
  um den Hash zu bilden — und bei Änderung ein zweites Mal fürs Staging. Ob sich stattdessen
  ETag oder Last-Modified nutzen lassen (ETag ist bei Multipart-Uploads kein MD5 des Inhalts
  und damit nicht ohne Weiteres verlässlich), gehört geklärt. Das Ergebnis kann eine neue
  `IndexSchema.VERSION` erfordern.

**Akzeptanzkriterien**

- [x] `docs.data-dir=s3://bucket/prefix` wird beim Start aufgelöst, ohne Codeänderung an
      Scanner oder Parsern
- [x] Ein vollständiger Scan über S3 findet Projekte, Versionen und `doc/` korrekt — der
      Nachweis, dass die Verzeichnis-Emulation trägt
- [x] Eine Sources-JAR aus S3 wird gelesen; das lokale Staging greift und die Kopie
      verschwindet danach wieder
- [x] Zugangsdaten kommen aus der Umgebung, stehen also nicht in der Konfiguration
- [x] Ein selbst gehosteter, nicht-AWS-Endpunkt funktioniert (Endpoint-Override,
      Path-Style)
- [x] Der lokale Pfad funktioniert unverändert weiter — S3 ist eine Alternative, kein Ersatz

*Gegen den echten Store geprüft: 11 Dateien, 2084 Chunks, keine Fehler; alle vier
MCP-Tools arbeiten auf den S3-Daten. Drei Fallen dabei gefunden und behoben — siehe
`CLAUDE.md`, Kernanforderung 0.*

### Task 13 — Deployment auf k3s mit Flux

Betrieb im eigenen Cluster, ausgerollt über Flux. Manifeste kommen aus Git, nicht per
`kubectl apply`.

**Ausgangslage (geprüft)**

- Forgejos **Container-Registry ist aktiv** (`/v2/` antwortet mit 401, der Normalfall).
- **OpenSearch läuft im selben Cluster** (`opensearch-master-0`, Pod-IP im k3s-Netz
  dem internen Pod-Netz). In-Cluster gehört deshalb der Service-Name in die Konfiguration, nicht
  den Ingress-Namen von außen.
- **`quarkus-smallrye-health` fehlt** — es gibt derzeit kein `/q/health`.
- Das Embedding-Modell ist **482 MB** und liegt nicht im Repository.

**Entscheidungen, die zu treffen sind**

- **Modell ins Image** (empfohlen) oder per initContainer/PVC. Fürs Image spricht das
  Layering: als eigener `COPY` **vor** dem Anwendungs-JAR wird die Schicht einmal gepusht
  und danach wiederverwendet. Inhaltlich gehören Modell und Code ohnehin zusammen — ein
  Modellwechsel erzwingt einen vollständigen Reindex.
- **Datenquelle**: nach Task 12 entweder ein PVC oder S3. Bei S3 entfällt das PVC ganz, was
  das Deployment deutlich vereinfacht.
- **Wo liegen die Manifeste** — entschieden: im GitOps-Repository `k8s-flux` unter
  `clusters/home/apps/kvasir/`, wie bei den übrigen Anwendungen dort. Dieses Repository enthält
  nur noch Code, Dockerfile und den Build-Workflow.

  Ausschlaggebend war nicht die Ablage, sondern der Neustart: Umgebungsvariablen aus einer
  ConfigMap werden allein beim Containerstart gelesen, eine geänderte ConfigMap ließe den Pod
  also unbeirrt weiterlaufen. Ein `configMapGenerator` hängt einen Inhalts-Hash an den Namen und
  erzwingt damit ein Neu-Ausrollen — das setzt aber voraus, dass ConfigMap und Deployment in
  **derselben** Kustomization liegen. Damit war die Frage entschieden: Konfiguration ändern soll
  ein Commit im GitOps-Repository sein, also gehört das Deployment dorthin.
- **Wie werden Secrets verwaltet** (Registry-Zugang, S3-Zugangsdaten) — entschieden: Sealed
  Secrets, erzeugt von `scripts/seal-secrets.sh` gegen den Zielcluster, abgelegt neben den
  übrigen Manifesten im GitOps-Repository.

**Akzeptanzkriterien**

- [ ] Ein Image wird gebaut und in die Forgejo-Registry gepusht; das Modell liegt in einer
      eigenen Schicht, sodass Folge-Builds sie nicht erneut übertragen
- [ ] Flux rollt die Anwendung aus einer `GitRepository`-Quelle aus; ein Commit an den
      Manifesten genügt für ein Update
- [ ] Ein `startupProbe` überbrückt die ~6 Sekunden, die das Laden des ONNX-Modells und der
      Dimensionscheck brauchen — ohne ihn tötet Kubernetes den Pod vor dem Start
- [ ] Liveness und Readiness laufen über `/q/health`; die Readiness bezieht die
      OpenSearch-Verbindung ein
- [ ] **`/admin/**` ist von außen nicht erreichbar**, nur `/mcp` liegt am Ingress. Das ist
      keine Betriebsentscheidung, sondern steht so in `CLAUDE.md`: der Pfad-Präfix existiert
      genau dafür
- [ ] OpenSearch wird über den Cluster-internen Service angesprochen, nicht über den Ingress
- [ ] Ressourcen-Limits sind gesetzt und zum Speicherbedarf von ONNX-Runtime plus JVM-Heap
      passend belegt, nicht geraten

## Übergreifend, nach Task 10 zu prüfen (nicht eigener Task, sondern Querschnitt)

- [ ] Backend ist über `quarkus.hibernate-search-standalone.elasticsearch.version`
      zwischen OpenSearch und Elasticsearch umschaltbar, ohne Code anzufassen
      (Umschaltung selbst muss nicht gegengetestet werden, da nur OpenSearch
      verfügbar ist — nur die Konfigurierbarkeit prüfen)
- [ ] Server läuft sowohl über stdio als auch HTTP ohne Codeänderung (nur Extension-Wahl)
