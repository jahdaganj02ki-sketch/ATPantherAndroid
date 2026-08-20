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

Der Schalter „Nur einen Monitor gleichzeitig zulassen“ ist standardmäßig aktiviert. Ist er aktiviert und die Lock-Konfiguration fehlt, startet der Monitor nicht. Ist er deaktiviert, arbeiten die Apps unabhängig voneinander.
