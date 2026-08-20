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

Der Appwrite-API-Key kann gemäß der gewünschten Konfiguration in Android und Windows eingegeben werden. Er wird dort verschlüsselt gespeichert und als `X-Appwrite-Key` an Appwrite gesendet. Wichtig: Der Key ist dadurch aus einer laufenden APK/EXE grundsätzlich extrahierbar; verwende deshalb nur einen eingeschränkten Key und niemals einen Organisations- oder Admin-Key.

Die Auswahl „Monitor läuft auf: Android/Windows“ bestimmt die aktive Plattform. Ist Android ausgewählt, startet Windows nicht; ist Windows ausgewählt, startet Android nicht. Ein Wechsel der Auswahl beendet den bisher aktiven Monitor beim nächsten Heartbeat. Ohne konfigurierte Sperre startet der Monitor sicherheitshalber nicht.
