# Mehrinstanzbetrieb: was bricht, wenn Kvasir auf mehreren Pods läuft

Kvasir läuft heute als **eine** Instanz. Dieses Dokument sammelt, was beim Hochskalieren auf
mehrere Pods passiert, und beschreibt die empfohlene Absicherung der Indexierung so genau, dass
sie ohne erneute Untersuchung gebaut werden kann.

Nichts davon ist umgesetzt. Wer `replicas: 2` setzt, sollte vorher Abschnitt 1 und 2 gelesen
haben — der erste Befund zeigt sich nicht als seltener Fehler, sondern sofort und bei jeder
Anfrage.

Die beiden Hälften sind unterschiedlicher Natur, und das entscheidet, wie viel man investieren
sollte. **Das Session-Problem löst sich von außen**: Die MCP-Spezifikation hat Sessions mit der
Revision 2026-07-28 entfernt, hier ist also eine Brücke gefragt und keine Dauerlösung. **Die
Indexierung löst niemand für uns** — das ist eigene Fachlogik und braucht eine eigene Antwort.

## 1. Was sofort bricht: die MCP-Sessions

**Vorweg die Einordnung, ohne die man hier falsch abbiegt:** Das Problem ist ein
Übergangsproblem. Die MCP-Spezifikation hat Sessions mit der Revision **2026-07-28** ersatzlos
entfernt. Wer heute Sticky Sessions oder gar einen verteilten Session-Store baut, baut
Infrastruktur für einen Mechanismus, den das Protokoll nicht mehr kennt.

### Der Befund

`quarkus-mcp-server-http` 1.10.7 hält Sessions im Speicher der jeweiligen JVM:

- `ConnectionManager:30` — `private final ConcurrentMap<String, ConnectionTimerId> connections
  = new ConcurrentHashMap<>();`
- `StreamableHttpMcpMessageHandler:171–178` — der Handler liest den Header `Mcp-Session-Id`,
  schlägt ihn in genau dieser Map nach und antwortet bei unbekannter Session mit
  `ctx.fail(404)`. Dieselbe Stelle noch einmal bei `:268` für den SSE-Strom auf `GET /mcp`.

Ein Client initialisiert also auf Pod A und bekommt dessen Session-ID. Verteilt der Service den
nächsten `tools/call` per Round Robin auf Pod B, kennt B diese ID nicht — 404. Das ist kein
Wettlauf, den man selten trifft, sondern der Normalfall bei zwei Pods.

**Am laufenden Server nachgemessen**

```
POST /mcp  tools/call  mit erfundener Mcp-Session-Id   -> HTTP 404
POST /mcp  tools/call  mit gueltiger  Mcp-Session-Id   -> HTTP 200
```

### Warum ein Session-Store in Redis oder einer DB nicht hilft

Die naheliegende Idee, die Sessions einfach zu teilen, scheitert nicht an der Extension, sondern
am Inhalt einer Session. Sie ist keine Sammlung von Daten, sondern ein Griff auf lebende
Ressourcen **dieses** Prozesses:

```java
// SseMcpConnection
private final HttpServerResponse response;      // die offene SSE-Verbindung
// StreamableHttpMcpConnection
private final List<SubsidiarySse> sseStreams;   // dito, mehrere
// McpConnectionBase
protected final AtomicReference<Status> status;
protected final ConcurrentMap<RequestId, Optional<String>> cancellationRequests;
```

Dazu hält `ConnectionManager` je Verbindung eine Vert.x-Timer-ID fürs Auto-Ping. Serialisierbar
ist davon nur die Hülle — ID, Status, Client-Info, Log-Level. Der Teil, für den die Session
*existiert*, ist ein Dateideskriptor. Läge die Hülle in Redis, fände Pod B die Session zwar,
könnte dem Client aber nichts schicken, weil der Strom an Pod A hängt. Verteilen ließe sich das
nur mit einem Message-Bus zwischen den Pods, über den B seine Nachricht an A weiterreicht — ein
eigenes Stück Infrastruktur, für das die Extension keinen Ansatzpunkt bietet.

Ansatzpunkt gäbe es ohnehin kaum: `ConnectionManager` ist eine konkrete `@Singleton`-Klasse ohne
Interface und ohne `@DefaultBean`, die Map ist `private final`, und injiziert wird über den
konkreten Typ. In 1.13.1 und 2.0.0.CR2 ist das unverändert — es ist kein Versäumnis, sondern
folgt daraus, dass die Spezifikation in die andere Richtung gegangen ist.

### Der Zielzustand: Protokollrevision 2026-07-28

Aus dem [Changelog](https://modelcontextprotocol.io/specification/2026-07-28/changelog):

> Remove protocol-level sessions and the `Mcp-Session-Id` header from the Streamable HTTP
> transport. (SEP-2567)

> Make MCP stateless: remove the `initialize`/`notifications/initialized` handshake. Every
> request now carries its protocol version and client capabilities in `_meta`
> (`io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientCapabilities`).
> (SEP-2575)

Aus den [Release Notes](https://blog.modelcontextprotocol.io/posts/2026-07-28/): „any request can
land on any instance behind a plain round-robin load balancer."

Weiteres, das dabei für den Betrieb zählt: `Mcp-Method` und `Mcp-Name` sind auf POSTs Pflicht,
ein Gateway kann also darauf routen und messen, ohne JSON zu parsen. Der GET-Endpunkt weicht
`subscriptions/listen`, einem langlebigen POST-Response-Strom, in den ein Client sich gezielt
einklinkt. SSE-Resumability (`Last-Event-ID`) entfällt — ein abgerissener Strom bedeutet, die
Anfrage neu zu stellen. Roots, Sampling und Logging sind deprecated; Kvasir benutzt keins davon.

Wie weit die Extension ist:

| Version | unterstützte Protokollversionen |
|---|---|
| **1.10.7** (im Einsatz) | bis `2025-11-25` |
| 1.13.1 | bis `2025-11-25` |
| **2.0.0.CR2** | zusätzlich **`2026-07-28`** |

Zwei Vorbehalte: 2.0.0 ist bislang ein Release Candidate, und die Umstellung ist beidseitig — der
Client muss `2026-07-28` ebenfalls sprechen. Ob die Agenten, die Kvasir tatsächlich benutzen, das
heute tun, ist **nicht geprüft** und wäre der erste Schritt vor einem Upgrade.

### Bis dahin

| Weg | Kosten |
|---|---|
| **`quarkus.mcp.server.http.streamable.dummy-init=true`** (ab 1.13: `auto-init`) | Legt für jede Anfrage mit unbekannter Session eine neue an. Javadoc: „can be used to simulate stateless communication. However, it's not efficient and some features may not work properly." Für Kvasirs vier Tools — reines Request/Response — reicht es. Die Brücke in Richtung Zielzustand. |
| **Sticky Sessions am Ingress**, Affinität über `Mcp-Session-Id` | Volles Protokoll bleibt erhalten, inklusive SSE. Aber: Der Ingress-Controller muss auf einen Header hashen können, ein Pod-Neustart beendet dessen Sessions — und die Bindung wird mit dem Protokoll-Upgrade wieder überflüssig. Nur sinnvoll, wenn servergetriebene Nachrichten gebraucht werden, was hier nicht der Fall ist. |

**Achtung beim Namen dieser Property, sie wurde umbenannt.** In 1.10.7 heißt sie `dummy-init`,
ab 1.13 `auto-init`. Wer den neueren Namen gegen 1.10.7 setzt, bekommt keinen Fehler, sondern
nur eine Warnzeile — und keine Wirkung:

```
WARN [io.quarkus.config] Unrecognized configuration key
"quarkus.mcp.server.http.streamable.auto-init" was provided; it will be ignored
```

Die Anwendung startet normal weiter und antwortet auf unbekannte Sessions unverändert mit 404.
Das ist die unangenehme Sorte Fehlkonfiguration: sieht gesetzt aus, tut nichts. Nachgemessen mit
`./mvnw test -Dquarkus.mcp.server.http.streamable.auto-init=true`.

`auto-init` ist dabei nicht bloß umbenannt, sondern näher an der neuen Spezifikation: Der Client
kann `clientInfo` und `clientCapabilities` über die `_meta`-Felder übermitteln und die
Protokollversion über den Header `Mcp-Protocol-Version` — genau die Mechanismen, die
`2026-07-28` verbindlich macht. Bei `dummy-init` gehen sie ersatzlos verloren. 1.13 bringt
zusätzlich `lazy-sse-init` (Default `true`): SSE wird erst initialisiert, wenn eine
SSE-abhängige API (`Progress`, `McpLog`, `Sampling`, `Roots`, `Elicitation`) wirklich benutzt
wird — Kvasir benutzt keine davon.

Der Server-Name entfällt für den Default-Server: `McpHttpServersRuntimeConfig` bindet die
Server-Map mit `@WithParentName` und `@WithUnnamedKey`, deshalb ist
`quarkus.mcp.server.http.streamable.…` der richtige Pfad und
`quarkus.mcp.server.<name>.http.streamable.…` nur für zusätzlich benannte Server nötig.

Die Entscheidung gehört **vor** das Hochskalieren. Danach fällt sie als Ausfall auf, nicht als
Entscheidung.

## 2. Die Indexier-Sperre gilt nur pro JVM

`CLAUDE.md` sagt zu, dass sich überlappende Scans gegenseitig ausschließen und eine abgelehnte
Anfrage `409 Conflict` samt laufendem Scan bekommt. Durchgesetzt wird das in
`ScanRegistry:43` (`public synchronized Started start(...)`) über einer JVM-lokalen
`ConcurrentHashMap` (`ScanRegistry:24`).

Bei mehreren Pods ist diese Zusage **unwahr**, ohne dass es auffällt:

- `POST /admin/index` wird vom Service verteilt. Zwei Pods starten je einen Vollscan, beide
  antworten `202` mit einer eigenen `scanId`. Die Überlappungsmatrix wird nur noch innerhalb
  einer Instanz geprüft.
- `ScanStatus` liegt ebenfalls nur im Speicher des startenden Pods.
  `GET /admin/index/status?scan-id=X` liefert `404`, sobald die Anfrage einen anderen Pod
  trifft. Der Status-Endpoint wird also schon durch zwei Pods unbrauchbar — auch dann, wenn
  überhaupt nur ein Scan läuft.

### Was dabei nicht kaputtgeht

Damit die Risiken nicht überzeichnet werden: Seit `IdUtils` sind Dokument-IDs **abgeleitet**.
Zwei Scans derselben Datei schreiben dieselben IDs mit demselben Inhalt, der Endzustand
konvergiert also. Genau dafür wurde gegen eine zufällige ID entschieden (siehe `CLAUDE.md`,
Kernanforderung 0).

Übrig bleiben drei Dinge:

- **Verschwendung.** Ein Vollscan über die 27 eingespielten Bibliotheken kostet rund vier
  Minuten CPU — 32 028 einzubettende Chunks bei gemessenen 125 Chunks/s auf sechs Kernen. Jeder
  weitere Pod macht dieselbe Arbeit noch einmal, und zwar auf einem Pod, der gleichzeitig
  Suchanfragen beantworten soll.
- **Refresh-Sturm.** `application.properties:111` setzt
  `indexing.plan.synchronization.strategy=read-sync`, deshalb läuft jeder Bulk mit
  `refresh=true`. Das ist nötig, weil `ChunkIngestor.deletePreviousChunks` (`:185`) den Index
  abfragt und die eigenen vorherigen Schreibvorgänge sehen muss. Es kostet aber einen
  indexweiten Refresh pro Datei — bei N gleichzeitigen Scans N-fach, und Refreshes schlagen auf
  die Suchlatenz **aller** Nutzer durch, nicht nur auf die des Scans.
- **Eine echte Korruptionslücke.** `ChunkIngestor.replace` (`:128`) löscht erst die bisherigen
  Chunks einer Datei und schreibt dann die neuen. Schreibt Pod A die Chunks einer Datei, löscht
  Pod B sie unmittelbar danach und stirbt vor dem eigenen Schreiben, bleibt der Fingerabdruck
  von A ohne Chunks zurück. Das ist exakt der Zustand, vor dem `IndexConsistencyCheck` warnt und
  der sich nicht von selbst schließt — siehe [`recovery.md`](recovery.md).

### Empfohlene Absicherung: eine Sperre in OpenSearch

Die naheliegende Alternative wäre ein eigenes Indexer-Deployment mit `replicas: 1` und die
MCP-Pods daneben ohne `/admin`. Das ist reine Manifest-Arbeit und trennt die Ressourcen sauber —
aber die Garantie hängt dann an Deployment-Disziplin. Wer versehentlich das falsche Deployment
hochskaliert, verliert sie lautlos. Eine Sperre gilt unabhängig von der Topologie.

Kein neues System nötig: OpenSearch ist bereits der einzige geteilte Zustand. Das Nicht-Ziel
„keine zentrale Datenbank" aus `CLAUDE.md` bezieht sich auf **Rohdaten**, nicht auf
Koordination.

- **Ein Lock-Dokument je Scope** in einem dritten Index (`kvasir-scan-lock`), angelegt mit
  `op_type=create`: Das schlägt fehl, wenn der Scope belegt ist. Damit entsteht genau die
  heutige Semantik, nur clusterweit statt pro JVM.
- **Der Scope ist der, über den schon heute verglichen wird** — Vollscan, `project`,
  `project+version`. Die Überlappungsregeln aus `CLAUDE.md` bleiben unverändert gültig, nur der
  Ort der Prüfung wandert. Zu beachten: Ein Vollscan muss gegen **jede** bestehende Sperre
  prüfen, nicht nur gegen einen gleichnamigen Schlüssel; `ScanRegistry.overlaps(...)` behandelt
  `null` als „deckt alles ab", und diese Asymmetrie muss die Abfrage nachbilden.
- **Lease statt Sperre.** Ein Pod, der während des Scans stirbt, darf seinen Bereich nicht für
  immer blockieren. Der laufende Scan schreibt ein Heartbeat-Feld fort; eine Sperre, deren
  Heartbeat älter als ein Vielfaches des Intervalls ist, gilt als verfallen und darf übernommen
  werden. **Das ist der Teil, der beim Bauen am ehesten schiefgeht**: Ein zu kurzes Intervall
  erklärt einen lebenden Scan für tot und lässt einen zweiten daneben laufen, ein zu langes
  blockiert nach einem Absturz unnötig lange. Die Übernahme selbst muss wieder über eine
  bedingte Schreiboperation laufen (`if_seq_no`/`if_primary_term`), sonst übernehmen zwei Pods
  dieselbe verfallene Sperre gleichzeitig.
- **Nebeneffekt, der zwei weitere Löcher schließt.** Wandert der Scan-Status mit in den Index,
  funktioniert `GET /admin/index/status` von jedem Pod aus, und die Historie überlebt Neustarts
  — heute ist ihr Verlust in `ScanRegistry` bewusst hingenommen. Damit daraus keine
  Schreiblast ohne Nutzen wird: die laufenden Zähler im Speicher des besitzenden Pods halten
  und nur bei Zustandswechseln plus periodisch (zusammen mit dem Heartbeat) persistieren.

## 3. Startvorgänge, die sich zwischen Pods ins Gehege kommen

Kleiner als die ersten beiden Punkte, aber sie fallen beim ersten Rollout auf:

- **`RrfPipeline.install(...)` (`:45`) schreibt bei jedem Start** `PUT
  /_search/pipeline/kvasir-rrf`. Die Pipeline ist clusterweit, nicht pro Pod. Inhaltsgleiches
  Schreiben ist harmlos; sobald sich die RRF-Gewichte ändern, gewinnt jedoch der zuletzt
  gestartete Pod — und ein alter Pod, der später aus irgendeinem Grund neu startet, setzt die
  Werte für alle wieder zurück. Während eines rollenden Updates gilt die Konfiguration also
  nicht der neuen, sondern der zuletzt gestarteten Version.
- **`schema-management.strategy=create-or-validate` (`application.properties:87`)**: Starten
  mehrere Pods gleichzeitig gegen einen noch nicht existierenden Index, könnten sie sich beim
  Anlegen überholen. *Als Risiko notiert, nicht nachgemessen* — der Fall tritt nur beim
  allerersten Start eines leeren Clusters auf.
- **`IndexConsistencyCheck` und `EmbeddingDimensionCheck`** laufen pro Pod und melden dasselbe
  N-fach. Harmlos, erklärt aber N-fache Warnungen im Log.
- **`DataStore`** prüft beim Start die Erreichbarkeit des Datenverzeichnisses und warnt nur,
  statt abzubrechen. Das bleibt pro Pod korrekt.

## 4. Ressourcen

- **Das Embedding-Modell liegt in-process**: 482 MB Dateien (`model.onnx` 449 MB,
  `tokenizer.json` 17 MB), von jedem Pod erneut geladen. Der tatsächliche Speicherbedarf zur
  Laufzeit — ONNX-Arenas plus JVM-Heap — ist **nicht gemessen** und muss vor dem Setzen von
  Limits ermittelt werden.
- **Einbetten skaliert schlecht über Kerne.** Gemessen mit `multilingual-e5-small` über den
  realen Bestand:

  | Kerne | Chunks/s |
  |-------|----------|
  | 1     | 60       |
  | 6     | 125      |
  | 14    | 184      |

  Sechs Kerne liefern gut das Doppelte eines einzelnen, nicht das Sechsfache. Der Grund steht in
  [`maven-import.md`](maven-import.md): der Median-Chunk ist 63 Zeichen kurz, der Fixaufwand je
  Inferenz dominiert. Für **Suchdurchsatz** sind deshalb mehr kleine Pods sinnvoller als wenige
  große; fürs **Indexieren** bringt beides wenig, und mehr Pods bringen dort ohnehin nichts,
  weil nur einer scannen soll.
- **`DataStore` kopiert Archive** von Nicht-Default-Dateisystemen in eine lokale Temp-Datei. Das
  betrifft nur den indexierenden Pod, ist aber bei der Größe des ephemeren Speichers zu
  berücksichtigen: die größte Sources-JAR im aktuellen Bestand ist 1,1 MB, ein Satz aus 27
  Bibliotheken zusammen 7,3 MB — unkritisch, solange keine großen Archive dazukommen.

## 5. Was heute schon mehrinstanzfähig ist

Damit klar wird, wie groß der Rest ist:

- **Die Suche ist zustandslos.** `search_docs`, `list_projects`, `list_versions` und
  `get_class_source` lesen ausschließlich aus OpenSearch; kein Pod hält etwas, das ein anderer
  bräuchte.
- **Chunk-IDs sind abgeleitet.** Gleichzeitige Schreiber erzeugen keine Duplikate, sondern
  dieselben Dokumente.
- **Die Readiness-Probe passt.** `SearchBackendHealthCheck` ist `@Readiness` und bewusst nicht
  `@Liveness` (`:32`, `:41`): Ein Pod mit unerreichbarem Backend wird aus dem Verkehr genommen,
  statt neu gestartet zu werden — genau das Verhalten, das man bei mehreren Pods will.

Zu tun bleiben damit drei Dinge, in dieser Reihenfolge der Beständigkeit:

1. **Die Sperre der Indexierung** (Abschnitt 2) — eigene Fachlogik, die niemand sonst löst, und
   die einzige der drei, an der eine Korruptionslücke hängt.
2. **Die Pipeline-Frage** (Abschnitt 3) — klein, aber beim ersten rollenden Update sichtbar.
3. **Die Sessions** (Abschnitt 1) — vorübergehend über `auto-init`, endgültig über das Upgrade
   auf eine Extension-Version, die `2026-07-28` spricht. Hier lohnt sich kein Bau, der über eine
   Konfigurationszeile hinausgeht.
