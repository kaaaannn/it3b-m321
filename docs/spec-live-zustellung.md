# Spezifikation: Live-Zustellung

Modul M321, Bewertung 2. Dieser Teil baut den Anzeigeweg der Chat-App: Jede Nachricht erscheint
sofort bei allen Clients, die im gleichen Raum verbunden sind.


---

## 1. Zweck und Abgrenzung

### 1.1 Was der Anzeigeweg tut

Seit Bewertung 1 geht jede Nachricht `POST → Exchange chat.messages → Queue chat.persist → batch-writer → PostgreSQL`.
Gespeichert ist sie damit, aber kein Client erfährt live davon. Neu:

1. Jede Instanz des `chat-service` hat eine **eigene Queue** am Fanout-Exchange `chat.messages`.
2. Die Instanz holt ihre Kopie jeder Nachricht aus dieser Queue.
3. Sie schiebt die Nachricht per **Server-Sent Events** an alle Clients, die an ihr auf `GET /stream?roomId=…` verbunden sind und zum gleichen Raum gehören.
4. Ein Client, dessen Verbindung abbricht, bekommt nach der Neuverbindung genau die Nachrichten nach, die ihm fehlen (`Last-Event-ID`).

Gespeichert bedeutet ja nur, dass die Nachricht in der Datenbank ist. Ohne einen Anzeigeweg müsste jeder Client immer wieder selbst nachfragen und neue Nachrichten würden erst verspätet oder gar nicht erscheinen. Ein chat funktioniert im prinzip nur wenn die Antworten sofort beim anderen sichtbar sind.

### 1.2 Was nicht Teil dieser Aufgabe ist

| Nicht Teil | Warum |
|---|---|
| Keycloak, Token-Prüfung | Laut Auftrag ausgeschlossen |
| Gateway (nginx), React, JavaFX | Laut Auftrag ausgeschlossen; der Lehrer verbindet sich von innen im Netz `chat-net` mit jeder Instanz einzeln |
| Räume anlegen, Mitglieder einladen | Laut Auftrag ausgeschlossen |
| Speichern | Macht weiterhin ausschliesslich der `batch-writer` über `chat.persist` |

Der `chat-service` veröffentlicht keinen Port auf dem Host. Er bleibt skalierbar, also **ohne `container_name`** in `docker-compose.yml`.

---

## 2. Vertrag

### 2.1 Was am Exchange ankommt

Quelle: `chat-service/.../MessageService.sendMessage` publiziert ein `Message`-Objekt mit
`Jackson2JsonMessageConverter` auf den Fanout-Exchange `chat.messages`, Routing-Key leer. Gemessen in
`docs/spec-batch-writer.md`, Abschnitt 2.1. Der Rumpf ist JSON mit genau fünf Feldern:

| Feld | Typ | Pflicht |
|---|---|---|
| `id` | UUID als String | ja |
| `roomId` | UUID als String | ja |
| `sender` | String | ja |
| `text` | String | ja |
| `sentAt` | ISO-8601 mit `Z` | ja |

`content_type: application/json`. Der Header `__TypeId__` ist nicht Teil des Vertrags (siehe batch-writer).

### 2.2 Was am Stream hinausgeht

| Punkt | Festlegung |
|---|---|
| Endpunkt | `GET /stream?roomId=<UUID>` am `chat-service`, zusätzlich `GET /api/stream?roomId=…` |
| Antwort | `Content-Type: text/event-stream`, Verbindung bleibt offen |
| Pro Nachricht ein Ereignis | `id:` = die Nachrichten-ID (dieselbe UUID wie in `chat.persist`), `data:` = dasselbe JSON wie in `chat.persist`, eine Zeile |
| Filter | Nur Nachrichten, deren `roomId` dem Parameter entspricht |
| Neuverbindung | Header `Last-Event-ID: <UUID>` → zuerst alle Nachrichten dieses Raums **nach** dieser ID in Reihenfolge, dann live |
| Fehlende oder ungültige `roomId` | `400 Bad Request` |

Beispiel eines Ereignisses:

```
id: 7f3c2a10-1b4e-4d9a-9c1e-0a1b2c3d4e5f
data: {"id":"7f3c2a10-…","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Hallo","sentAt":"2026-11-20T08:00:00Z"}

```

---

## 3. Verhalten

### 3.1 Normalfall: eine Nachricht von POST bis `data:`

```
Client --POST /messages--> chat-service (Instanz A) --publish--> Exchange chat.messages
                                                                      |
                              +---------------------------------------+------------------+
                              v                                       v                  v
                     Queue chat.persist                      Queue chat.live.A    Queue chat.live.B
                              |                                       |                  |
                         batch-writer                          Instanz A liest     Instanz B liest
                                                                      |                  |
                                                        SSE an Clients Raum X   SSE an Clients Raum X
```

1. `POST` wie bisher: UUID und `sentAt` werden gesetzt, Nachricht wird publiziert, Antwort `202`.
2. Der Fanout-Exchange legt eine Kopie in **jede** gebundene Queue: `chat.persist` und jede `chat.live.<instanz>`.
3. Jede Instanz liest aus ihrer eigenen Queue mit **einem** Verbraucher-Thread.
4. Die Instanz legt die Nachricht in ihren Zwischenspeicher für den Raum (Abschnitt 3.6) und schreibt sie als Ereignis in jede offene Stream-Verbindung dieses Raums.
5. Ereignisse werden in Ankunftsreihenfolge geschrieben. Damit bleibt die Reihenfolge pro Absender erhalten.

### 3.2 Zwei Instanzen (S3)

Jede Instanz bekommt jede Nachricht, weil jede Instanz eine eigene Queue am Fanout hat. Ein Client an Instanz A und
ein Client an Instanz B sehen beide alle 400 Nachrichten, jede genau einmal. Ein Client in Raum B bekommt keine
Nachricht aus Raum A, weil die Instanz vor dem Schreiben den Raum vergleicht.

Beim Batch writer sollte jede Nachricht nur 1 mal gespeichert werden. Deshalb teilen sich die Instanzen eine queue und jede Nachricht geht nur an eine Instanz. Beim Anzeigeweg hängen an jeder Instanz andere Clients, deshalb muss jede Instanz jede Nachricht bekommen.

### 3.3 Instanz verschwindet (S4)

Die Queue `chat.live.<instanz>` ist `autoDelete`: Sobald ihr einziger Verbraucher weg ist, löscht RabbitMQ sie.
Nach `--scale chat-service=1` ist nach spätestens 30 Sekunden nur noch eine Live-Queue am Exchange.
`chat.persist` und `chat.dlq` sind davon nicht betroffen.

Der Exchange würde dann weiter Kopien in eine Queue legen, die keiner mehr liest. Sie würde bis die Grenze voll ist weiterlaufen und belegt Speicher im Broker. Bei jeder neuer Skalierung käme dann eine weitere solche "leichen queue" dazu.

### 3.4 Datenbank und batch-writer weg (S5)

Der Anzeigeweg braucht **keine** Datenbank: Queue → Instanz → Stream. Alle 100 Nachrichten erscheinen beim Client
innerhalb von 5 Sekunden. Die Kopien in `chat.persist` bleiben liegen, weil die Queue dauerhaft ist; nach dem
Neustart von `batch-writer` und `postgres` werden sie geschrieben. Auch das Nachholen per `Last-Event-ID` braucht
keine Datenbank (Abschnitt 3.6).

### 3.5 Instanz hängt (S6)

Hängt eine Instanz (`docker compose pause`), holt sie nichts mehr ab, der Exchange legt aber weiter Kopien in
ihre Queue. Die Queue ist begrenzt: `x-max-length: 1000`, bei Überlauf wird die **älteste** Nachricht verworfen,
dazu `x-message-ttl: 30000` (30 Sekunden). Die Queue hält damit nie mehr als 1000 Nachrichten. Der Client an der
anderen Instanz bekommt alle 5000. Nach `docker compose unpause` liest die Instanz weiter und stellt neue
Nachrichten wieder zu.

Die Live-Queue ist ja nur für die sofortige Anzeige da. Wenn dort eine Nachricht verloren geht, ist sie trotzdem über chat.persist gespeichert und kann nachgeholt werden. Bei chat.persist ist das anders, das ist der einzige Weg in die Datenbank und was dort weggeworfen wird, ist für immer weg.


### 3.6 Client bricht ab und kommt zurück (S7)

Jede Instanz hält pro Raum einen **Zwischenspeicher im Arbeitsspeicher** mit den letzten 500 Nachrichten
(Ringpuffer, älteste fällt raus). Bei einer Verbindung mit `Last-Event-ID`:

1. Die Instanz sucht die ID im Zwischenspeicher des Raums.
2. Gefunden: alle Nachrichten **nach** dieser ID werden in Reihenfolge als Ereignisse geschrieben, danach live.
3. Nicht gefunden (zu alt oder unbekannt): Es wird nichts nachgeholt, nur ein Kommentar `: last-event-id unbekannt`
   gesendet, danach live. Der Client holt den Rest über `GET /api/messages` aus der Datenbank.

Keine Nachricht wird doppelt geschrieben, weil der Zwischenspeicher ab der gefundenen ID exklusiv weiterzählt.

Das Nachholen muss auch gehen, wenn die Datenbank weg ist (S5). Der Zwischenspeicher wird direkt aus der Live-Queue gefüllt und braucht deshalb weder postgres noch den batch-writer. Ausserdem ist er schneller, weil keine Datenbankabfrage nötig ist.

---

## 4. Queues

| Queue | Name | Lebensdauer | Grenzen | Bei Erreichen der Grenze |
|---|---|---|---|---|
| Live-Queue je Instanz | `chat.live.<zufällige ID>` | `durable=false`, `autoDelete=true`: verschwindet, wenn die Instanz weg ist | `x-max-length: 1000`, `x-message-ttl: 30000` | Älteste Nachricht wird verworfen; abgelaufene Nachrichten verfallen |
| `chat.persist` | unverändert | dauerhaft, Quorum | `x-delivery-limit: 20` | → `chat.dlq` (siehe batch-writer) |
| `chat.dlq` | unverändert | dauerhaft | keine | – |

Exchange `chat.messages`: Fanout, `durable=true`, `autoDelete=false`, wie bisher.

Einstellbar über `.env` (Vorlage `.env.example`):

| Variable | Standard | Wofür |
|---|---|---|
| `LIVE_QUEUE_MAX_LENGTH` | 1000 | Obergrenze der Live-Queue |
| `LIVE_QUEUE_TTL_MS` | 30000 | Ablaufzeit einer Live-Nachricht |
| `LIVE_REPLAY_BUFFER_SIZE` | 500 | Nachrichten pro Raum im Zwischenspeicher |

Das sind im Prinzip Startwerte und nicht gemessen. 30 Sekunden reichen für einen kurzen Hänger, danach ist eine Nachricht für die Live-Anzeige sowieso veraltet. 1000 kleine JSON-Nachrichten sind nur ein paar hundert KB, deshalb belastet eine hängende Instanz den Broker kaum.
---

## 5. Abnahmekriterien

Alle Befehle vom Wurzelverzeichnis des Forks. Der Stream wird von innen geprüft, zum Beispiel mit
`docker compose exec chat-service curl -N -s "http://localhost:8080/stream?roomId=11111111-1111-1111-1111-111111111111"`.

| Nr | Prüfung | Erwartet |
|---|---|---|
| A1 (S1) | `mvn clean test` | `BUILD SUCCESS`, alle Tests grün, ohne laufenden Stack |
| A2 (S2) | `cp .env.example .env && docker compose up -d --build && docker compose ps` | alle Dienste `running`, kein `0.0.0.0:` in PORTS |
| A3 (S3) | `docker compose up -d --scale chat-service=2`; je ein `curl -N …/stream?roomId=A` pro Instanz, einer mit Raum B; je 200 `POST /messages` über jede Instanz | beide A-Streams zeigen 400 Ereignisse mit 400 verschiedenen `id:`, pro Absender in Sendereihenfolge; B-Stream zeigt 0 |
| A4 (S4) | `docker compose exec rabbitmq rabbitmqctl list_queues name consumers` vor und 30 s nach `--scale chat-service=1` | vorher zwei `chat.live.*` mit je 1 Consumer, nachher eine; `chat.persist` und `chat.dlq` bleiben |
| A5 (S5) | `docker compose stop batch-writer postgres`; 100 × `POST`; danach `docker compose start batch-writer postgres` | Stream zeigt 100 Ereignisse ≤ 5 s; nach dem Neustart `SELECT count(*)` ist um 100 gestiegen |
| A6 (S6) | `docker compose pause <instanz>`; 5000 × `POST` über die andere; `rabbitmqctl list_queues name messages` mehrfach; `unpause` | Queue der pausierten Instanz nie > 1000; anderer Stream zeigt 5000; nach `unpause` kommen neue Nachrichten wieder |
| A7 (S7) | Stream öffnen, 1 `POST`, Stream schliessen, 50 × `POST`, nach 10 s `curl -N -H "Last-Event-ID: <id der ersten>" …/stream?roomId=…` | genau die 50 fehlenden, in Reihenfolge, keine doppelt, danach live |
| A8 (S8) | `grep -rn "\.stream()\|\.map(\|Collectors\." chat-service/src/main/java`; `git ls-files \| grep -x ".env"` | beide leer; jede Klasse und Methode hat einen Kommentar |

---

## 6. Verworfene Varianten

| Verworfen | Grund |
|---|---|
| Eine gemeinsame Live-Queue für alle Instanzen | Dann bekäme jede Nachricht nur eine Instanz, die Clients an der anderen sähen sie nie |
| Nachholen aus der Datenbank | Scheitert in S5 (Datenbank gestoppt) und hängt vom batch-writer ab |
| Live-Queue dauerhaft und unbegrenzt | Eine hängende Instanz füllt den Broker und zieht die gesunde Instanz mit runter |
| WebSocket statt SSE | Der Auftrag verlangt SSE mit `id:`/`data:`; SSE bringt `Last-Event-ID` im Browser von Haus aus mit |

Polling, also dass der Client alle paar Sekunden GET /api/messages abfragt, habe ich verworfen. Das gibt Verzögerung und viele unnötige Anfragen, und in S5 würde der Client ohne Datenbank gar nichts mehr sehen.

---

## 7. Offene Punkte

1. **Grenzen sind Startwerte.** 1000 / 30 s / 500 sind nicht gemessen; S6 ist die Messübung dazu.
2. **Zwischenspeicher pro Instanz.** Verbindet sich ein Client nach dem Abbruch mit einer *anderen* Instanz, kennt
   deren Zwischenspeicher dieselben Nachrichten nur, wenn sie zur gleichen Zeit lief. Für die Bewertung kein Problem
   (der Lehrer verbindet sich mit jeder Instanz einzeln), für später offen.
3. **Viele offene Streams.** Jede Verbindung belegt einen Thread des Webservers; Grenze noch nicht getestet.

Reihenfolge über Instanzen. Die Reihenfolge ist nur innerhalb von einer Instanz garantiert. Wenn ein Absender kurz nacheinander über zwei verschiedene Instanzen schickt, können sich seine Nachrichten überholen. Mit einer Sequenznummer pro Absender könnte man das lösen.