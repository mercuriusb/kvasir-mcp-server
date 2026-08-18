# Jackson im Überblick

Jackson ist eine Bibliothek zum Lesen und Schreiben von JSON in Java. Dieses Dokument gilt
versionsübergreifend und liegt deshalb unter `doc/`.

## Module

Jackson ist in mehrere Module aufgeteilt:

- `jackson-core` — der Streaming-Parser und -Generator
- `jackson-databind` — die Abbildung zwischen JSON und Java-Objekten
- `jackson-annotations` — die Annotationen, die beide Seiten steuern

## Wann welches Modul

Wer nur Token für Token durch ein Dokument gehen will, kommt mit `jackson-core` aus.
Objektbindung, polymorphe Typen und Modulregistrierung leben in `jackson-databind`.
