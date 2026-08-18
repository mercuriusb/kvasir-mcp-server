# JobRunr im Überblick

JobRunr führt Java-Hintergrundjobs aus, die als Lambda übergeben und in einer Datenbank
persistiert werden. Dieses Dokument gilt versionsübergreifend.

## Kernbegriffe

Ein *Job* ist eine serialisierte Methodenreferenz samt Argumenten. Der *BackgroundJobServer*
holt fällige Jobs aus dem Speicher und führt sie aus.
