# Lock-Konfiguration der beiden Clients

Nach dem Appwrite-Setup müssen in beiden Apps identische Werte eingetragen werden:

## Android

Datei:

```text
app/src/main/java/com/alditalk/panther/lock/MonitorLockConfig.kt
```

## Windows

Datei:

```text
MonitorLockConfig.cs
```

Einzutragen sind:

- Function-Execution-URL
- Appwrite Project-ID
- derselbe lange zufällige `LOCK_SHARED_SECRET`

Der Appwrite-API-Key gehört **nicht** in Android oder Windows, sondern ausschließlich in die Appwrite Function-Umgebungsvariablen.

Die Auswahl „Monitor läuft auf: Android/Windows“ bestimmt die aktive Plattform. Ist Android ausgewählt, startet Windows nicht; ist Windows ausgewählt, startet Android nicht. Ein Wechsel der Auswahl beendet den bisher aktiven Monitor beim nächsten Heartbeat. Ohne konfigurierte Sperre startet der Monitor sicherheitshalber nicht.
