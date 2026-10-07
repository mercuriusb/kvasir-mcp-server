# Wiederherstellung, Reindexierung und Änderungen an der Indexstruktur

Was zu tun ist, wenn der Index verloren, unvollständig oder veraltet ist — und warum die
naheliegende Abkürzung dabei die gefährlichste Option ist.

## Die Grundregel: zwei Indizes, ein Zustand

Die Anwendung schreibt in **zwei** Indizes, die zusammengehören:

| Index | Inhalt |
|---|---|
| `kvasir-doc-chunk` | Die Chunks — das, was durchsucht wird |
| `kvasir-indexed-file` | Pro Datei: SHA-256 des Inhalts und die Schema-Version, unter der sie indexiert wurde |

Der zweite ist der Grund, warum ein wiederholter Scan Sekunden statt Minuten braucht: Er sagt, was
sich seit dem letzten Mal geändert hat. Genau deshalb ist er auch die Gefahrenquelle.

### Die beiden Halb-Wiederherstellungen

|  | Folge |
|---|---|
| Nur **Chunks** zurückgespielt | Alle Fingerabdrücke fehlen, der nächste Scan baut alles neu auf. Langsam, harmlos, heilt sich selbst. |
| Nur **Fingerabdrücke** zurückgespielt | Der Scan überspringt **jede** Datei, weil alle als unverändert gelten. Der Chunk-Index bleibt leer, Suchen liefern nichts, und **nichts sagt warum**. |

Der zweite Fall ist der teure. Deshalb gilt:

> **Snapshot und Restore immer über beide Indizes gemeinsam.**

Und falls es doch passiert, wird es beim Start gemeldet:

```
WARN  Index inconsistency: [jobrunr] recorded as indexed but hold no chunks. A scan will skip
      their files because the fingerprints say they are unchanged, so the gap will not close by
      itself. Reindex them with POST /admin/index?project=<name>&force=true, or restore both
      indexes together next time - 'kvasir-doc-chunk' and 'kvasir-indexed-file' belong to
      each other.
```

`IndexConsistencyCheck` sucht dafür nach Projekten, die Fingerabdrücke haben, aber keinen einzigen
Chunk. Der Check **repariert nicht** — die Fingerabdrücke auf Verdacht beim Start zu löschen wäre
eine destruktive Vermutung. Er benennt das Problem und den Weg heraus.

## Snapshot und Restore

### Einmalig: Repository einrichten

**Noch nicht möglich auf der aktuellen Instanz.** `path.repo` ist dort leer, und ohne diese
Einstellung lehnt OpenSearch ein Dateisystem-Repository ab:

```
repository_exception: location [kvasir] doesn't match any of the locations specified by
path.repo because this setting is empty
```

Das ist eine Cluster-Einstellung, keine Anwendungseinstellung — sie muss in der `opensearch.yml`
bzw. im Kubernetes-Manifest gesetzt und der Cluster neu gestartet werden. Alternativ ein
S3-Repository, das kein `path.repo` braucht, dafür Zugangsdaten.

```yaml
# opensearch.yml
path.repo: ["/mnt/snapshots"]
```

```bash
curl -X PUT "$OS/_snapshot/kvasir" -H 'Content-Type: application/json' -d '{
  "type": "fs",
  "settings": { "location": "kvasir" }
}'
```

### Snapshot ziehen — immer beide Indizes

```bash
curl -X PUT "$OS/_snapshot/kvasir/snap-$(date +%Y%m%d)?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{
    "indices": "kvasir-doc-chunk-*,kvasir-indexed-file-*",
    "include_global_state": false
  }'
```

Die physischen Indizes heißen `…-000001`; die Aliasse `-read`/`-write` kommen beim Restore mit.

### Zurückspielen

```bash
curl -X POST "$OS/kvasir-doc-chunk-*,kvasir-indexed-file-*/_close"
curl -X POST "$OS/_snapshot/kvasir/snap-20260816/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' -d '{
    "indices": "kvasir-doc-chunk-*,kvasir-indexed-file-*"
  }'
```

### Danach: nur das Delta nachziehen

```bash
curl -X POST "http://localhost:8080/admin/index"
```

Mehr ist nicht nötig. Der Scan vergleicht jede Datei gegen ihren Fingerabdruck aus dem Snapshot;
was sich seither geändert hat, wird neu indexiert, alles andere übersprungen. Belegt am Zählerbild:

```
nach vollständigem Verlust:   scanned=6 indexed=6 skipped=0 chunks=2075
unmittelbar danach erneut:    scanned=6 indexed=0 skipped=6 chunks=0
```

## Wenn der Index falsch ist, die Fingerabdrücke aber das Gegenteil behaupten

```bash
curl -X POST "http://localhost:8080/admin/index?project=jobrunr&force=true"
```

`force=true` ignoriert die Fingerabdrücke und baut alles im gewählten Bereich neu auf. Das ist die
Notbremse für den Fall der halben Wiederherstellung — und für jeden anderen, in dem der Index
nachweislich nicht zum Inhalt passt.

Gegenprobe aus dem realen Lauf, nachdem die Chunks von `jobrunr` gelöscht wurden:

```
ohne force: indexed=0 skipped=2   -> die Lücke bleibt
mit force:  indexed=2 chunks=4    -> geschlossen
```

Auf einem erzwungenen Lauf wird **nicht** aufgeräumt: Die Fingerabdrücke wurden ja absichtlich
ignoriert und sagen nichts darüber aus, was noch auf der Platte liegt.

## Gelöschte Dateien

Verschwindet eine Datei aus `data/`, wird sie beim nächsten Scan ihres Bereichs samt Chunks aus dem
Index entfernt. Der Scan selbst kann eine gelöschte Datei nie *sehen* — er läuft nur über das, was
da ist. Deshalb merkt er sich, welche Dateien er angetroffen hat, und entfernt am Ende alles, was
zwar als indexiert vermerkt ist, ihm aber nicht begegnet ist.

```
Scan: scanned=1 skipped=1 removedFiles=1 removedChunks=2
```

Zwei Feinheiten:

- Eine Datei, deren Verarbeitung **scheitert**, gilt trotzdem als angetroffen — sie liegt ja auf der
  Platte. Ein Parser-Fehler löscht also nichts.
- Aufgeräumt wird nur, wenn der Durchlauf des Projekts **fehlerfrei** war. Bricht das Verzeichnis
  mitten im Lauf weg, wäre sonst jede noch nicht erreichte Datei „gelöscht".

### Ein vollständig entferntes Projekt bleibt liegen

Verschwindet nicht eine einzelne Datei, sondern das ganze Projektverzeichnis, greift dieses
Aufräumen **nicht**. `DataScanner.scan` läuft über `directoriesIn(dataRoot)`, also über das, was da
ist; für ein Verzeichnis, das es nicht mehr gibt, wird `scanProject` nie aufgerufen und damit auch
`forgetVanishedFiles` nie. Chunks und Fingerabdrücke des Projekts bleiben im Index, gleichgültig wie
oft gescannt wird.

Das ist mehr als Ballast: `list_projects` beantwortet sich aus dem Index und nennt das Projekt
weiter. Ein Agent, für den diese Liste laut Tool-Beschreibung die Quelle der Wahrheit ist, wird also
in eine Suche geschickt, deren Treffer aus einer Quelle stammen, die es nicht mehr gibt — und die
Treffer sehen aus wie jede andere Antwort.

Bis das behoben ist, hilft nur Löschen von Hand, und zwar in **beiden** Indizes, sonst entsteht
genau die halbe Wiederherstellung von oben:

```bash
for IDX in kvasir-doc-chunk-write kvasir-indexed-file-write; do
  curl -X POST "$OS/$IDX/_delete_by_query" -H 'Content-Type: application/json' \
    -d '{"query":{"terms":{"project":["demo","other"]}}}'
done
```

`project` ist in beiden Entities ein `@KeywordField`, die Term-Query trifft also exakt.

Behebbar wäre es, weil die Fingerabdrücke sämtliche indexierten Projekte kennen: ein **Vollscan**
könnte die Menge der angetroffenen Projekte gegen die Menge der vermerkten halten und die Differenz
entfernen. Nur ein Vollscan — ein auf ein Projekt eingeschränkter Lauf weiß über die anderen nichts
und würde sie alle für verschwunden halten. Und nicht auf einem `force`-Lauf, wo die Fingerabdrücke
ohnehin nichts über den Bestand aussagen.

## Änderungen an der Indexstruktur

### Der Fall, den `IndexSchema.VERSION` löst

Nicht jede Änderung ändert das Mapping. Würde ein Parser Markdown künftig anders schneiden, wären
die gespeicherten Chunks veraltet — aber das Mapping wäre identisch, **und der Hash der Datei auch**.
Ein Scan würde alles überspringen und niemand würde es merken.

Deshalb ist die Schema-Version Teil des Fingerabdrucks. `IndexSchema.VERSION` **hochzählen** genügt:
Beim nächsten Scan gilt jede Datei als geändert und wird neu aufgebaut, ohne dass jemand daran
denken muss, den Fingerabdruck-Index von Hand zu löschen.

Hochzählen bei:

- einem Feld, das in `DocChunk` hinzukommt, wegfällt oder den Typ wechselt
- einem Wechsel des Embedding-Modells oder seiner Dimension
- geänderten Chunking-Regeln in einem Parser
- einem geänderten ID-Schema in `IdUtils`

### Der Fall, den sie nicht löst

Ändert sich das **Mapping selbst** unverträglich, kommt die Anwendung gar nicht erst so weit.
`schema-management.strategy=create-or-validate` verweigert den Start und nennt die Felder:

```
HSEARCH000520: Hibernate Search encountered failures during bootstrap.
  field 'schemaVersion'
```

Das ist gewollt — die Alternative wäre stilles Weiterlaufen mit falschem Mapping. Der Weg heraus:

1. Den betroffenen Index löschen (nur den — `kvasir-indexed-file` und `kvasir-doc-chunk` haben
   getrennte Mappings und ändern sich selten gemeinsam).
2. Anwendung starten; der Index wird mit dem neuen Mapping angelegt.
3. `POST /admin/index` — bei gelöschtem Fingerabdruck-Index baut sich alles neu auf, bei gelöschtem
   Chunk-Index braucht es zusätzlich `force=true`, weil die Fingerabdrücke sonst alles überspringen.

Genau dieser Fall trat beim Einführen von `schemaVersion` real ein: Nur `kvasir-indexed-file`
musste weg, `kvasir-doc-chunk` blieb unangetastet.

### Welche Änderung erzwingt was

| Änderung | Reindex nötig? |
|---|---|
| Neues Feld in `DocChunk` | Ja — Mapping ändert sich, `create-or-validate` bricht ab |
| Feld aggregierbar/projizierbar machen | Ja — ist eine Mapping-Eigenschaft |
| Andere Embedding-Dimension | Ja — und zusätzlich sind alle bestehenden Vektoren wertlos |
| Anderes Embedding-Modell, gleiche Dimension | Ja, inhaltlich: Vektoren aus zwei Modellen sind nicht vergleichbar. Das Mapping merkt es **nicht** — nur `IndexSchema.VERSION` fängt das ab |
| Geändertes Chunking in einem Parser | Ja, inhaltlich — dito, nur über `IndexSchema.VERSION` |
| Geändertes ID-Schema in `IdUtils` | Ja — jedes Dokument bekommt eine neue ID. Die alten werden **nicht** überschrieben, sondern erst von `deletePreviousChunks` beim Neuindexieren der Datei geräumt; ohne Reindex stünden beide Stände nebeneinander |
| Neuer Parser für einen neuen Dateityp | Nein — bestehende Chunks bleiben gültig, die neuen Dateien kommen beim nächsten Scan dazu |
| Geänderte Tool-Beschreibung, RRF-Gewichte, Paging | Nein — nichts davon steht im Index |

Die dritte bis fünfte Zeile sind die tückischen: Das Mapping bleibt gültig, der Start gelingt, und
die Suche liefert trotzdem Unsinn. Sie sind der eigentliche Daseinsgrund von `IndexSchema.VERSION`.

## Noch offen

- **Blue/Green-Reindex über die Aliasse.** `PrefixedIndexLayoutStrategy` legt bereits
  `…-000001` mit `-read` und `-write` an — die Struktur für einen Umbau ohne Suchausfall ist damit
  vorhanden: in `…-000002` schreiben, am Ende `-read` umhängen. Ob Hibernate Search das ausreichend
  unterstützt, ist nicht untersucht.
- **Automatische Snapshots nach Zeitplan** — Betrieb, nicht Anwendungslogik.
- **Ein vollständig entferntes Projekt aus dem Index räumen** (siehe oben). Der Vollscan hat alles,
  was er dafür braucht; es fehlt der Abgleich der angetroffenen gegen die vermerkten Projekte.
