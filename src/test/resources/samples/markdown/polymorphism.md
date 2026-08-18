Dieses Dokument beschreibt, wie polymorphe Typen behandelt werden.
Es steht bewusst vor der ersten Überschrift.

# Polymorphic deserialization

Wenn ein Feld als Basistyp deklariert ist, muss beim Lesen entschieden werden,
welche konkrete Klasse gemeint war.

## Type identifiers

Der Typ wird über ein zusätzliches Feld im JSON transportiert.

```java
@JsonTypeInfo(use = Id.NAME, property = "type")
```

## Fallbacks

Ist kein Bezeichner vorhanden, greift der konfigurierte Default.

# Grenzen

Rekursive Typhierarchien werden nicht aufgelöst.
