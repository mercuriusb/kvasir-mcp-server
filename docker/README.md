# Lokal starten mit Docker Compose

Kvasir und ein eigenes OpenSearch, ohne Cluster und ohne S3. Gedacht zum Ausprobieren und
Entwickeln; der Betrieb läuft über Flux (siehe das GitOps-Repository `k8s-flux`).

## Voraussetzungen

Zwei Dinge liegen nicht in Git und müssen vor dem ersten Bauen da sein:

```bash
scripts/download-embedding-model.sh    # ~482 MB Embedding-Modell
./mvnw clean package                   # target/quarkus-app
```

Beides landet im Image — das Dockerfile kopiert es hinein, deshalb ist der Build-Kontext das
Projektwurzelverzeichnis und nicht dieses hier.

## Starten

```bash
cd docker
docker compose up
```

Der erste Lauf dauert: das Image ist rund 1 GB, OpenSearch braucht einen Moment, und Kvasir lädt
beim Start etwa sechs Sekunden lang das ONNX-Modell.

Der Korpus liegt in `docker/data/` und ist zunächst leer — ein Scan meldet dann `COMPLETED` mit
null Dateien, was richtig ist und nicht nach einem Fehler aussehen sollte. Wie das Verzeichnis
aufgebaut sein muss, steht in `data/README.md`.

Danach:

```bash
curl -XPOST http://localhost:8080/admin/index          # data/ indexieren
curl http://localhost:8080/admin/index/status
curl http://localhost:8080/q/health
```

Das MCP-Endpunkt liegt unter `http://localhost:8080/mcp`; die Aufrufe dafür stehen fertig in
`httpclient/mcp.http`.

## Was hier anders ist als im Cluster

| | Cluster | hier |
|---|---|---|
| Korpus | S3 (`s3x://silo…`) | lokales `docker/data`, schreibgeschützt eingehängt |
| OpenSearch | geteilte Instanz | eigener Container, Sicherheits-Plugin aus |
| Zugangsdaten | SealedSecret | keine nötig |
| Image | Registry-Tag | aus dem Arbeitsverzeichnis gebaut |

**Ohne Zugangsdaten funktioniert es, weil `docs.data-dir` ein gewöhnlicher Pfad ist.** Dann wird
gar kein S3-Provider angefasst; die Verweise auf `AWS_*` in `application.properties` lösen zu
Leerwerten auf, und leere Werte werden bewusst nicht als System-Property gesetzt.

## Wenn etwas nicht startet

**Kvasir wartet auf OpenSearch, und zwar zwingend.** Ohne erreichbares OpenSearch bricht Hibernate
Search beim Bootstrap ab und die Anwendung startet gar nicht — deshalb der `healthcheck` und
`condition: service_healthy`. Ein Container, der sofort wieder aussteigt, hat fast immer diese
Ursache.

**Fehlt das neural-search-Plugin, scheitert der Start mit einem Hinweis darauf.** Die hybride Suche
braucht es für die `hybrid`-Query und den `score-ranker-processor` der RRF-Pipeline. Die offizielle
Distribution bringt es mit; nachsehen lässt es sich mit:

```bash
curl http://localhost:9200/_cat/plugins | grep neural
```

**`vm.max_map_count`** ist auf manchen Hosts zu klein für OpenSearch. Unter Linux:

```bash
sudo sysctl -w vm.max_map_count=262144
```

**Änderungen am Code** brauchen ein neues Image:

```bash
./mvnw clean package && docker compose up --build
```
