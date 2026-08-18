# Release Notes 2.22.1

Diese Datei ist versionsspezifisch und liegt deshalb unter `2.22.1/`.

## Behobene Fehler

Der Streaming-Parser meldete bei sehr langen Zeichenketten, die über eine Puffergrenze
hinausgingen, eine falsche Position im Dokument.

## Bekannte Einschränkungen

Rekursive Typhierarchien werden bei der polymorphen Deserialisierung weiterhin nicht aufgelöst.
