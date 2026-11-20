# Umsetzungsplan: Live-Zustellung

Modul M321, Bewertung 2. Grundlage: `docs/spec-live-zustellung.md`. Jeder Schritt hat ein Ziel, den Auftrag an
die KI im Wortlaut, einen Test und – nach dem Schritt – das Feld «Geprüft und korrigiert». Ein Commit pro Schritt.

Dieser Plan wird gepusht, bevor der erste Schritt beginnt. Änderungen danach nur mit Änderungsgrund in einem
eigenen Commit.

Regeln für jeden Auftrag: `CLAUDE.md` gilt (eine Anweisung pro Zeile, keine Streams, deutsche Kommentare über
jeder Klasse und Methode, englische Bezeichner, kein Lombok). Keine neue Bibliothek ohne Rückfrage.
Commit-Nachricht mit zweiter Zeile `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## Schritt 1 – Stream-Endpunkt, noch ohne Broker

**Ziel:** `GET /stream?roomId=…` antwortet mit `text/event-stream` und hält die Verbindung offen. Noch kommt
nichts an – aber der Endpunkt, die Verwaltung der offenen Verbindungen pro Raum und die Validierung stehen.

**Warum an dieser Stelle:** Der Endpunkt ist das Ende des Weges. Steht er zuerst, kann jeder weitere Schritt
gegen ihn getestet werden, ohne dass Broker-Logik dazwischen ist.

**Auftrag an die KI:**
> Lege im chat-service das Paket `stream` an mit zwei Klassen. `StreamRegistry` verwaltet pro Raum die offenen
> `SseEmitter` in einer thread-sicheren Liste (`ConcurrentHashMap<UUID, CopyOnWriteArrayList<SseEmitter>>`), mit
> Methoden `register(roomId, emitter)`, `remove(roomId, emitter)` und `publish(roomId, message)`, die ein
> Ereignis mit `id` = Nachrichten-ID und `data` = JSON an jeden Emitter des Raums schreibt und Emitter entfernt,
> deren Schreiben scheitert. `StreamController` bietet `GET /stream` und `GET /api/stream` mit Parameter `roomId`
> (Pflicht, UUID, sonst 400), erzeugt einen `SseEmitter` ohne Zeitlimit, registriert ihn und meldet ihn bei
> `onCompletion` und `onTimeout` ab. Swagger-Annotationen wie bei `MessageController`. Keine Streams, Kommentare
> auf Deutsch über jeder Klasse und Methode. Dazu einen `@WebMvcTest` `StreamControllerTest`: Aufruf mit gültiger
> `roomId` liefert Status 200 und `Content-Type` beginnt mit `text/event-stream`; Aufruf ohne `roomId` liefert 400.

**Test:** `mvn test` im Ordner `chat-service` grün. Von Hand: `curl -N -i localhost:8080/stream?roomId=11111111-1111-1111-1111-111111111111` zeigt die Kopfzeile `Content-Type: text/event-stream` und bleibt offen.

**Commit:** `feat(chat-service): GET /stream oeffnet eine SSE-Verbindung pro Raum`

**Geprüft und korrigiert:** Erster Lauf `mvn test`: 10 Tests, 2 rot (`StreamControllerTest`, «Content type not set»).
Ursache: Seit Spring Framework 6.2 schickt der Server die Kopfzeilen einer SSE-Antwort erst mit dem ersten Schreiben
in den Stream – der Controller hat nur die Verbindung geöffnet, aber nichts geschrieben. Das hätte auch den
`curl -i`-Test von Hand scheitern lassen. Korrektur: `StreamController` schreibt beim Öffnen sofort eine
SSE-Kommentarzeile `: verbunden` (Methode `sendOpeningComment`); Kommentarzeilen sind im SSE-Format vorgesehen und
werden vom Client überlesen. Zweiter Lauf: 10 Tests grün. Zusätzlich zum Auftrag: `onError` meldet den Emitter ebenfalls
ab, und eine `roomId`, die keine UUID ist, wird mit eigenem Test auf 400 geprüft. Test ist unverändert geblieben.

---

## Schritt 2 – Eigene Live-Queue pro Instanz

**Ziel:** Jede Instanz legt beim Start ihre Queue `chat.live.<zufällige ID>` am Fanout-Exchange an, liest daraus
mit einem Verbraucher-Thread und gibt jede Nachricht an die `StreamRegistry` weiter. Die Queue ist `autoDelete`,
begrenzt auf 1000 Nachrichten und 30 Sekunden.

**Warum an dieser Stelle:** Damit ist der Weg Exchange → Instanz → Stream durchgängig. S3, S4, S5 und S6 hängen
alle an dieser einen Queue-Definition.

**Auftrag an die KI:**
> Erweitere `rabbit/RabbitConfiguration` im chat-service: eine Bean `liveQueue()` mit Namen
> `chat.live.` + zufällige UUID, `durable=false`, `exclusive=true`, `autoDelete=true`, Argumente `x-max-length`
> und `x-message-ttl` aus den Einstellungen `chat-service.live-queue.max-length` (Standard 1000) und
> `chat-service.live-queue.ttl-ms` (Standard 30000); eine `Binding` dieser Queue an `chatExchange`. Lege im Paket
> `stream` die Klasse `LiveMessageListener` an: `@RabbitListener` auf der Live-Queue (Name über
> `#{liveQueue.name}`), Nebenläufigkeit 1, bekommt den Rumpf als `byte[]`, wandelt ihn mit dem `ObjectMapper`
> in ein `Message`-Objekt um (eigene Zielklasse im Code, kein Typ-Header, wie im batch-writer), und ruft
> `StreamRegistry.publish`. Unlesbare Nachrichten werden mit `WARN` geloggt und verworfen – nicht in die DLQ,
> weil Live-Nachrichten nicht aufgehoben werden. Trage die zwei Einstellungen in `application.yml` ein, mit
> Umgebungsvariablen `LIVE_QUEUE_MAX_LENGTH` und `LIVE_QUEUE_TTL_MS`, und in `.env.example` mit Kommentar.
> Entferne in `docker-compose.yml` das `container_name` des chat-service, damit `--scale` geht. Test mit
> Testcontainers wie im batch-writer (`TestInfrastructure` im chat-service nachbauen): Nachricht direkt auf den
> Exchange publizieren, ein zuvor geöffneter Stream desselben Raums bekommt genau ein Ereignis mit dieser `id`;
> ein Stream eines anderen Raums bekommt nichts.

**Änderung am Plan (20.11.2026):** `exclusive=false` → `exclusive=true`. Grund: RabbitMQ 4 (Image
`rabbitmq:4-management-alpine`) lehnt Queues ab, die weder dauerhaft noch exklusiv sind
(«Feature transient_nonexcl_queues is deprecated»), die Anwendung startet so nicht. `durable=false` und
`autoDelete=true` aus der Spezifikation (Abschnitt 4) bleiben; die Queue verschwindet weiterhin, sobald die Instanz
weg ist (S4), und bleibt auf 1000 Nachrichten / 30 Sekunden begrenzt (S6). Die Spezifikation nennt `exclusive`
nicht und bleibt unverändert.

**Test:** `mvn test` grün. Von Hand im Stack: Stream öffnen, `POST /messages`, Ereignis erscheint.
`rabbitmqctl list_queues name consumers` zeigt `chat.live.…` mit 1 Consumer.

**Commit:** `feat(chat-service): eigene Live-Queue je Instanz, Nachrichten gehen an die offenen Streams`

**Geprüft und korrigiert:** Erster Lauf `mvn test`: Kontext startet nicht, RabbitMQ 4 lehnt die Live-Queue ab
(«Feature transient_nonexcl_queues is deprecated»): Queues, die weder dauerhaft noch exklusiv sind, sind dort nicht
mehr erlaubt. Korrektur: `exclusive=true` statt `false` (Planänderung im eigenen Commit davor); `durable=false` und
`autoDelete=true` bleiben wie in der Spezifikation. Zweiter Lauf: 2 rot im `LiveMessageListenerTest`, «Cannot
deserialize value of type byte[]». Ursache: Spring Boot setzt den JSON-Umwandler aus `RabbitConfiguration` auch beim
Empfangen ein und versucht, das JSON selbst in `byte[]` zu verwandeln. Korrektur: eigener Hörer-Behälter
`liveListenerContainerFactory`, der die Bytes unverändert weitergibt. Ausserdem hing der Test-Client beim Schliessen
(`HttpURLConnection.disconnect()` wartet auf den Lese-Thread, der nie fertig wird) und der Lauf am Ende 30 s (das
sanfte Herunterfahren von Spring Boot wartet auf offene Streams). Korrektur: Test-Client über einen `Socket`, im Test
`server.shutdown=immediate`. Letzter Lauf: 12 Tests grün. Zusätzlich zum Auftrag: Nachrichten ohne `id`/`roomId`
werden verworfen, mit eigenem Test für eine unlesbare Nachricht; die zwei Umgebungsvariablen werden in
`docker-compose.yml` durchgereicht. Von Hand im Stack: `docker compose ps` ohne `0.0.0.0:`, Stream geöffnet, `POST
/messages` → Ereignis mit derselben `id` erscheint sofort; `list_queues` zeigt `chat.live.…` mit 1 Consumer,
`durable false`, `exclusive true`, `auto_delete true`. Test ist unverändert geblieben.

---

## Schritt 3 – Nachholen mit Last-Event-ID

**Ziel:** Ein Client, der mit `Last-Event-ID` zurückkommt, bekommt genau die Nachrichten nach dieser ID, in
Reihenfolge, ohne Doppelte, danach live. Ohne Datenbank.

**Warum an dieser Stelle:** Baut auf Schritt 2 auf: Der Zwischenspeicher wird von derselben Stelle gefüllt, die
auch die Streams bedient.

**Auftrag an die KI:**
> Lege im Paket `stream` die Klasse `ReplayBuffer` an: pro Raum ein Ringpuffer (`ArrayDeque<Message>`) mit
> höchstens `chat-service.replay-buffer-size` Einträgen (Standard 500, Umgebungsvariable
> `LIVE_REPLAY_BUFFER_SIZE`), Methoden `add(message)` und `after(roomId, lastEventId)`, die eine Liste aller
> Nachrichten nach der gegebenen ID in Reihenfolge liefert, oder eine leere Liste mit Kennzeichen «unbekannt»,
> wenn die ID nicht im Puffer ist. Zugriff synchronisiert. `LiveMessageListener` ruft vor `publish` zuerst
> `add`. `StreamController` liest den Header `Last-Event-ID`: Ist er gesetzt, werden zuerst die Nachrichten aus
> `after(...)` als Ereignisse geschrieben, bei «unbekannt» ein SSE-Kommentar `: last-event-id unbekannt`, dann
> wird der Emitter registriert. Test mit Testcontainers: Stream öffnen, eine Nachricht publizieren, Stream
> schliessen, 50 Nachrichten publizieren, neuen Stream mit `Last-Event-ID` der ersten öffnen – er liefert genau
> die 50 in Reihenfolge, keine doppelt; danach eine weitere Nachricht publizieren, sie kommt live an.

**Test:** `mvn test` grün (Neuverbindungs-Test). Von Hand: Ablauf A7 aus der Spezifikation.

**Commit:** `feat(chat-service): Nachholen nach Verbindungsabbruch ueber Last-Event-ID`

**Geprüft und korrigiert:** Erster Lauf `mvn test`: 18 Tests grün. Weil ein Test, der sofort grün ist, noch nichts
beweist, Gegenprobe: Aufruf von `replayMissedMessages` im `StreamController` auskommentiert → `StreamReplayTest` rot
(erwartet die 50 IDs, bekommen nichts); Aufruf wiederhergestellt → wieder grün. Beim Durchgehen eine Lücke gefunden,
die der Test allein nicht zeigt: Kommt eine Nachricht genau zwischen dem Nachholen und dem Anmelden der Verbindung an,
steht sie nicht im Nachgeholten und wird auch nicht live geschrieben. Korrektur: `LiveMessageListener` (add + publish)
und `StreamController` (nachholen + register) arbeiten unter derselben Sperre `synchronized (replayBuffer)`.
Zusätzlich zum Auftrag: `ReplayResult` als Rückgabe von `after(...)` (Liste plus Kennzeichen «unbekannt»),
`ReplayBufferTest` (4 Tests ohne Spring), ein Test für eine unbekannte `Last-Event-ID`, `LIVE_REPLAY_BUFFER_SIZE` in
`.env.example` und `docker-compose.yml`. Von Hand A7 im Stack: Stream geöffnet, `vor-dem-abbruch` live erhalten,
Stream geschlossen, 50 × `POST`, Neuverbindung mit `Last-Event-ID` → genau `verpasst1` bis `verpasst50` in
Reihenfolge, `vor-dem-abbruch` nicht doppelt, danach `wieder-live` live. Hinweis: Eine ältere `.env` ohne
`LIVE_REPLAY_BUFFER_SIZE` gibt die Variable leer weiter, dann startet der Dienst nicht – `.env` neu aus der Vorlage
kopieren. Test ist unverändert geblieben.

---

## Schritt 4 – Zwei-Instanzen-Test

**Ziel:** Ein Test beweist, dass zwei Live-Queues am Exchange jede Nachricht beide bekommen und jede nur einmal.

**Warum an dieser Stelle:** S3 ist das Herz der Aufgabe; der Test muss ohne laufenden Stack gehen.

**Auftrag an die KI:**
> Schreibe `TwoInstancesTest` mit Testcontainers: Neben der Live-Queue der laufenden Anwendung wird im Test eine
> zweite Queue `chat.live.test-zweite-instanz` mit denselben Eigenschaften an den Exchange gebunden. Zwei Streams
> auf Raum A und einer auf Raum B werden geöffnet. 20 Nachrichten für Raum A werden publiziert. Erwartet: beide
> A-Streams erhalten 20 Ereignisse mit 20 verschiedenen IDs in Publikationsreihenfolge, der B-Stream keines, und
> die zweite Queue enthält ebenfalls 20 Nachrichten (`rabbitTemplate.receive` zählen). Keine Streams im
> Java-Sinn, Kommentare auf Deutsch.

**Test:** `mvn clean test` im Wurzelverzeichnis grün, ein Lauf.

**Commit:** `test(chat-service): zwei Live-Queues erhalten jede Nachricht genau einmal`

**Geprüft und korrigiert:** _(nach dem Schritt ausfüllen)_

---

## Schritt 5 – Stack, README, Abgabe

**Ziel:** Frischer Klon fährt hoch, kein Port nach aussen, README-Tabelle «Stand» nachgeführt, Tag gesetzt.

**Auftrag an die KI:**
> Prüfe `docker-compose.yml`: chat-service ohne `container_name`, nur `expose`, neue Umgebungsvariablen
> durchgereicht. Ergänze in `README.md` die Tabelle «Stand» um die Live-Zustellung und die neuen Variablen in
> der Tabelle der Umgebungsvariablen, dazu den `curl -N`-Befehl zum Prüfen des Streams von innen. Keine Änderung
> am Code.

**Test:** `cp .env.example .env && docker compose up -d --build && docker compose ps` – kein `0.0.0.0:`.
Szenarien A3 bis A7 aus der Spezifikation einmal von Hand durchspielen.

**Commit:** `docs: Stand im README nachgefuehrt, Abgabe bewertung-2`

**Danach:** `git tag bewertung-2 && git push origin main --tags`

**Geprüft und korrigiert:** _(nach dem Schritt ausfüllen)_

---

## Was dieser Plan bewusst nicht enthält

Keycloak, Gateway, React, JavaFX, Raumverwaltung: laut Auftrag nicht Teil der Bewertung. Keine Änderung am
`batch-writer`.
