# Korpus für den lokalen Lauf

Dieses Verzeichnis wird von `docker-compose.yaml` schreibgeschützt nach `/data` in den Container
gehängt und dort indexiert. Es ist **bewusst nicht** das `data/` im Projektwurzelverzeichnis: jenes
ist eine Spielwiese für die Entwicklung, dieses hier der Korpus, den der Container sieht. Wer sie
zusammenlegt, ändert unbemerkt beides zugleich.

## Aufbau

```
data/
  <projekt>/
    doc/                       ← gilt für alle Versionen
      *.md  *.txt  *.html
    <version>/
      <projekt>-<version>-sources.jar    ← genau eine Sources-JAR
      *.md  *.txt  *.html                ← nur für diese Version
```

`<projekt>` und `<version>` sind die Verzeichnisnamen, nichts weiter — sie werden zu den
Pflichtfeldern jedes Chunks und damit zu den Werten, nach denen `search_docs` filtert.

Alles unter `doc/` bekommt **keine** Version und wird bei jeder Versionsabfrage mitgefunden; in
Ergebnissen ist es an `version: null` erkennbar. Das ist der Unterschied zwischen „gilt überall"
und „gilt für 2.22.1", und der Grund, warum versionsübergreifende Doku nicht je Version kopiert
werden muss.

Das mitgelieferte `beispiel/` zeigt nur die Form und ist leer. Ein echtes Projekt sieht so aus:

```
data/jackson/doc/ueberblick.md
data/jackson/2.22.1/jackson-core-2.22.1-sources.jar
data/jackson/2.22.1/release-notes.md
```

## Danach

Neue Dateien werden nicht von selbst bemerkt — Indexieren ist ein bewusster Schritt:

```bash
curl -XPOST http://localhost:8080/admin/index
curl http://localhost:8080/admin/index/status
```

Unveränderte Dateien überspringt der Scan anhand von Pfad und Hash, ein erneuter Aufruf ist also
billig. Gelöschte Dateien verschwinden beim nächsten vollständigen Lauf auch aus dem Index.

Sources-JARs können groß sein: nicht versehentlich mit committen.
