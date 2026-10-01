# Spezifikation: batch-writer

Modul M321, Bewertung 1. Dieser Dienst schliesst den Schreibweg der Chat-App: jede Nachricht,
die der `chat-service` auf den Exchange legt, landet dauerhaft in PostgreSQL.

Massstab dieser Datei: wer sie liest, kann den Dienst bauen, ohne mich zu fragen.

---

## 1. Zweck und Abgrenzung

### 1.1 Was der Dienst tut

Der `batch-writer` ist ein Spring-Boot-Dienst **ohne Weboberfläche**. Er hat genau eine Aufgabe:

1. Er hört auf der Queue `chat.persist`.
2. Er sammelt eingehende Nachrichten zu Paketen.
3. Er schreibt jedes Paket mit **einer** Anweisung in die Tabelle `message`.
4. Erst **nach** dem Commit bestätigt er dem Broker den Empfang.

Er ist der **einzige** Dienst, der in die Tabelle `message` schreibt. Der `chat-service` liest sie
nur. Das ist keine Geschmacksfrage: liefen zwei Instanzen des `chat-service` und würden beide
schreiben, hätten wir zwei Schreiber auf derselben Tabelle und keine Kontrolle über die Bündelung.

### 1.2 Was der Dienst bewusst nicht tut

| Nicht Teil dieses Dienstes | Warum |
|---|---|
| Nachrichten entgegennehmen (`POST`) | Macht der `chat-service`. Der `batch-writer` hat keinen offenen Port und keine HTTP-Schnittstelle |
| Verlauf ausliefern (`GET`) | Macht der `chat-service` |
| Räume und Mitgliedschaften prüfen | Laut Auftrag ausdrücklich nicht Teil der Bewertung. Der `batch-writer` schreibt, was ankommt, und urteilt nicht über Zugehörigkeit |
| Token prüfen | Die Nachricht hat die Prüfung im `chat-service` bereits hinter sich. Was in der Queue liegt, gilt als bewilligt |
| Live-Zustellung an Clients | Läuft über eine eigene Queue und SSE im `chat-service`, nicht hier |
| Archivierung und Statistik | Später, eigener Plan |

Der Dienst veröffentlicht **keinen** Port auf dem Host. Er spricht nur innerhalb des
Docker-Netzes `chat-net` mit `rabbitmq` und `postgres`.

---

## 2. Vertrag: was genau auf der Queue ankommt

### 2.1 Woher ich das weiss

Zwei Quellen, nicht geraten:

**Erstens der Quelltext des Absenders.** In `chat-service/src/main/java/ch/benedict/m321/chat/message/MessageService.java`
baut `sendMessage` ein `Message`-Objekt und übergibt es:

```java
rabbitTemplate.convertAndSend(RabbitConfiguration.EXCHANGE_NAME, "", message);
```

Der Exchange heisst `chat.messages` und ist ein Fanout-Exchange, der Routing-Key ist leer.
Gewandelt wird mit `Jackson2JsonMessageConverter`.

**Zweitens eine Messung am Broker.** Ich habe eine Hilfsqueue an den Exchange gebunden, eine
Nachricht gesendet und sie wieder herausgeholt. Das kam heraus:

```json
{
  "payload_bytes": 198,
  "exchange": "chat.messages",
  "routing_key": "",
  "properties": {
    "delivery_mode": 2,
    "priority": 0,
    "content_type": "application/json",
    "content_encoding": "UTF-8",
    "headers": { "__TypeId__": "ch.benedict.m321.chat.message.Message" }
  },
  "payload": "{\"id\":\"ec52e3f3-5411-413e-a617-0f8c81ab9509\",\"roomId\":\"11111111-1111-1111-1111-111111111111\",\"sender\":\"lernende1\",\"text\":\"Erste Nachricht durch den Broker\",\"sentAt\":\"2026-09-11T12:36:52.816460100Z\"}"
}
```

### 2.2 Der Rumpf

JSON-Objekt, UTF-8, mit genau fünf Feldern:

| Feld | Typ im JSON | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | String, UUID-Form | Identität der Nachricht. Kommt vom `chat-service`, nicht von der Datenbank | ja |
| `roomId` | String, UUID-Form | In welchen Raum die Nachricht gehört | ja |
| `sender` | String | Benutzername des Absenders | ja |
| `text` | String | Der Nachrichtentext | ja |
| `sentAt` | String, ISO-8601 mit `Z` | Zeitpunkt des Sendens, in UTC, mit Nanosekunden | ja |

### 2.3 Die Eigenschaften der Nachricht

`delivery_mode: 2` heisst dauerhaft: der Broker schreibt die Nachricht auf die Platte und sie
übersteht seinen Neustart. `content_type: application/json` sagt, wie der Rumpf zu lesen ist.

**Der Header `__TypeId__` ist nicht Teil des Vertrags.** Er entsteht als Nebenwirkung des
Wandlers im `chat-service` und nennt dessen Java-Klasse. Der `batch-writer` darf sich **nicht**
darauf stützen, und zwar aus zwei Gründen:

1. Er bindet zwei Dienste über einen Klassennamen aneinander. Wird die Klasse im `chat-service`
   umbenannt, bricht der `batch-writer`, ohne dass jemand ihn angefasst hat.
2. Szenario S5 der Bewertung legt dieselbe Nachricht direkt in die Queue, ausdrücklich **nur**
   mit dem Header `content_type: application/json`. Eine Umsetzung, die `__TypeId__` erwartet,
   scheitert daran, obwohl die Dublettenerkennung funktioniert.

**Folge für die Umsetzung:** Der Dienst liest den Rumpf selbst und nennt Jackson die Zielklasse
im Code. Er benutzt also **keinen** typgesteuerten Wandler am Listener. Das ist die wichtigste
Einzelentscheidung dieser Spezifikation.

### 2.4 Was als ungültig gilt

Eine Nachricht ist ungültig, wenn mindestens eines zutrifft:

- Der Rumpf ist kein lesbares JSON.
- `id` oder `roomId` fehlt oder ist keine UUID.
- `sender` oder `text` fehlt oder ist leer.
- `sentAt` fehlt oder ist kein lesbarer Zeitpunkt.

Ungültige Nachrichten sind **dauerhaft** kaputt. Ein Wiederholen würde nie helfen, also gehen sie
nach `chat.dlq` (Abschnitt 3.5).

---

## 3. Verhalten

### 3.1 Normalfall

```
chat.persist  ->  Paket sammeln  ->  JSON lesen  ->  ein Batch-INSERT  ->  Commit  ->  ACK
```

Gesammelt wird nach zwei Regeln, und es gilt die, die zuerst eintritt:

| Regel | Standardwert | Warum |
|---|---|---|
| Paketgrösse | 500 Nachrichten | Ein `INSERT` mit 500 Zeilen statt 500 Anweisungen |
| Zeitlimit | 200 ms | Damit bei wenig Betrieb nichts liegen bleibt |

Beide Werte sind über Umgebungsvariablen einstellbar (Abschnitt 4.4). Der Vorabruf (`prefetch`)
muss **mindestens** so gross sein wie die Paketgrösse, sonst kann ein Paket nie voll werden.

Geschrieben wird mit einer einzigen Anweisung pro Paket:

```sql
INSERT INTO message (id, room_id, sender, text, sent_at)
VALUES (?, ?, ?, ?, ?)
ON CONFLICT (id) DO NOTHING
```

Die ganze Methode steht in **einer** Transaktion. Daraus folgt: ein Paket ist entweder ganz
geschrieben oder gar nicht, und pro Paket entsteht genau ein Commit.

**Begründung der Bündelung.** `PLANUNG.md` rechnet mit sehr hoher Last. Einzelne `INSERT`-Anweisungen
kosten je einen Netzwerk-Weg, ein Parsen und einen Commit. Szenario S4 macht daraus eine harte
Vorgabe: tausend Nachrichten dürfen höchstens hundert Transaktionen auslösen. Mit Paketen von
fünfhundert sind es zwei bis drei. Einzelschreiben wäre um den Faktor zehn zu viel.

### 3.2 Fehlerfall: dieselbe Nachricht kommt zweimal (S5)

**Verhalten.** Die zweite Zeile wird verworfen, die erste bleibt stehen. Es gibt genau eine Zeile,
und in `chat.dlq` landet nichts.

**Umsetzung.** `ON CONFLICT (id) DO NOTHING`. Die `id` ist Primärschlüssel und kommt vom Absender.

**Begründung.** Der Broker stellt mindestens einmal zu. Bestätigen wir erst nach dem Commit, kann
dasselbe Paket nach einem Absturz ein zweites Mal ankommen. Das ist gewollt: lieber doppelt als
verloren. Damit die Dublette harmlos bleibt, muss der Schreibvorgang idempotent sein, also zweimal
ausgeführt dasselbe Ergebnis haben wie einmal. Die Alternative, die Datenbank die `id` vergeben zu
lassen, würde genau das unmöglich machen: jede Wiederholung erzeugte neue Zeilen, und man könnte
echte von unechten Dubletten hinterher nicht mehr unterscheiden, denn zwei Leute dürfen dasselbe
Wort schreiben.

### 3.3 Fehlerfall: die Datenbank ist kurz weg (S7)

**Verhalten.** Keine Nachricht geht verloren. Der Dienst läuft weiter und braucht **keinen**
Neustart von Hand. Sobald die Datenbank zurück ist, wird geschrieben.

**Umsetzung.**

1. Der Schreibversuch wirft eine Ausnahme.
2. Der Dienst schreibt eine `WARN`-Zeile mit Grund und Paketgrösse.
3. Er wartet eine einstellbare Pause, Standard fünf Sekunden.
4. Er gibt die Ausnahme weiter. Das Paket wird **nicht** bestätigt und vom Broker erneut zugestellt.

**Begründung der Pause.** Ohne sie entsteht eine heisse Schleife: der Broker stellt sofort wieder
zu, der Versuch scheitert sofort wieder, und der Dienst belastet Broker und Datenbank im
Millisekundentakt, ohne Fortschritt. Die Pause ist zugleich der Rückstau nach hinten: solange sie
läuft, holt dieser Dienst nichts Neues, und die Queue wächst sichtbar. Das ist richtig so, denn
eine wachsende Queue ist eine Warnung, kein Fehler.

**Rechnung zur Zustellgrenze.** Die Queue hat `x-delivery-limit: 20`. Ein Ausfall von fünfzehn
Sekunden bei fünf Sekunden Pause ergibt etwa drei erneute Zustellungen. Das liegt klar unter der
Grenze, die Nachrichten bleiben also in der Queue und wandern nicht in die DLQ. Wer die Pause
verkürzt, muss die Grenze erhöhen.

**Unterschied, der zählt.** Ein Ausfall der Verbindung ist vorübergehend, ein kaputter Datensatz
ist es nicht. Nur beim ersten wird wiederholt. Wie der Dienst die zwei unterscheidet, steht in 3.5.

### 3.4 Fehlerfall: zwei Instanzen laufen gleichzeitig (S6)

**Verhalten.** Beide Instanzen hängen an derselben Queue. Alle Nachrichten sind in der Tabelle,
keine doppelt.

**Umsetzung.** Beide Instanzen deklarieren dieselbe Queue `chat.persist`. RabbitMQ gibt jede
Nachricht an **genau einen** Verbraucher, nicht an beide. Dieses Muster heisst konkurrierende
Verbraucher. Zusätzlich greift `ON CONFLICT (id) DO NOTHING` als Netz, falls eine Nachricht nach
einem Absturz der einen Instanz der anderen zugestellt wird.

**Begründung.** Hier zeigt sich der Unterschied zwischen Exchange und Queue. Der Fanout-Exchange
verteilt an **jede** gebundene Queue, also bekommt der Live-Weg eine Kopie und der Schreibweg eine
Kopie. Innerhalb **einer** Queue wird dagegen aufgeteilt. Hätte jede Instanz ihre eigene Queue,
würde jede Nachricht zweimal geschrieben, und nur der Primärschlüssel würde das noch retten. Eine
gemeinsame Queue ist also nicht Zufall, sondern die Bedingung dafür, dass Skalieren überhaupt
funktioniert.

### 3.5 Fehlerfall: eine Nachricht lässt sich nie schreiben

**Verhalten.** Die kaputte Nachricht geht nach `chat.dlq` und wird bestätigt. Alle anderen
Nachrichten desselben Pakets werden geschrieben. Die Queue läuft weiter.

**Umsetzung in drei Stufen:**

1. **Lesen.** Ist der Rumpf kein gültiges JSON oder fehlt ein Pflichtfeld, geht die Nachricht
   sofort nach `chat.dlq`. Sie kommt nie bis zur Datenbank.
2. **Paket schreiben.** Scheitert das Paket, wird geprüft: handelt es sich um einen Verbindungsfehler,
   greift 3.3, also wiederholen. Handelt es sich um einen Datenfehler, folgt Stufe drei.
3. **Einzelweg.** Das Paket wird ein einziges Mal Zeile für Zeile geschrieben. Jede Zeile, die für
   sich gelingt, ist damit gerettet. Nur die Zeile, die auch allein scheitert, geht nach `chat.dlq`.

**Begründung.** Ohne Stufe drei nimmt eine einzige kaputte Nachricht vierhundertneunundneunzig
gesunde mit in die DLQ. Der Einzelweg kostet einmalig Zeit, aber nur im Fehlerfall, und er ist der
Unterschied zwischen «eine Nachricht ist kaputt» und «ein Paket ist verloren». Diese Entscheidung
steht als offener Punkt in `PLANUNG.md` und wird hier beantwortet.

### 3.6 Was der Dienst beim Start tut

1. Er verbindet sich mit `rabbitmq` und deklariert Exchange, Queues und Bindung (Abschnitt 4.3).
   Deklarieren ist idempotent: existiert alles schon, passiert nichts.
2. Er stellt sicher, dass Tabelle und Index existieren (Abschnitt 4.2).
3. Er fängt an zu hören.

Ist der Broker beim Start nicht da, versucht Spring AMQP die Verbindung von selbst immer wieder.
Der Dienst stürzt deshalb nicht ab, wenn er schneller hochfährt als RabbitMQ. Das ist wichtig,
weil `docker compose up` alle Dienste gleichzeitig startet.

---

## 4. Datenmodell und Konfiguration

### 4.1 Tabelle und Index

```sql
CREATE TABLE IF NOT EXISTS message (
    id       UUID         PRIMARY KEY,
    room_id  UUID         NOT NULL,
    sender   VARCHAR(100) NOT NULL,
    text     TEXT         NOT NULL,
    sent_at  TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_message_room_time ON message (room_id, sent_at DESC);
```

Begründete Entscheide:

| Entscheid | Begründung |
|---|---|
| `id` ist Primärschlüssel und kommt vom Absender | Macht das Schreiben idempotent, siehe 3.2. Ohne das ist kein Wiederholen möglich |
| `sent_at` ist `TIMESTAMPTZ`, ohne `DEFAULT now()` | Gefragt ist der Moment des **Sendens**, nicht des Schreibens. Mit einem Standardwert hätten alle fünfhundert Nachrichten eines Pakets dieselbe Zeit, und die Reihenfolge im Chat wäre erfunden |
| `room_id` **ohne** Fremdschlüssel auf `room` | Abweichung vom bisherigen Schema, bewusst. Räume sind laut Auftrag nicht Teil dieser Bewertung, und die Reihenfolge zwischen Diensten ist nicht garantiert: eine Nachricht kann vor ihrem Raum ankommen. Mit Fremdschlüssel würde sie dann abgelehnt und landete in der DLQ, obwohl nichts kaputt ist. Die Prüfung, ob ein Raum existiert, gehört in den `chat-service`, wo sie am Token hängt |
| Index auf `(room_id, sent_at DESC)` | Der Verlauf wird immer pro Raum und nach Zeit gelesen. Genau diese Abfrage stellt der `chat-service` |
| Kein Index auf `sender` | Niemand fragt danach. Jeder Index kostet bei jedem Schreibvorgang Zeit, und Schreiben ist hier der Engpass |

### 4.2 Wo das Schema entsteht

Die Tabelle `message` gehört dem `batch-writer`, weil er ihr einziger Schreiber ist. Er legt sie
deshalb beim Start selbst an, mit `CREATE TABLE IF NOT EXISTS`, also wiederholbar und ohne Schaden
bei jedem Neustart.

Die Tabellen `room` und `room_member` bleiben im Init-Skript `db/01-schema.sql`, das das
Postgres-Image beim ersten Start ausführt.

**Warum nicht alles im Init-Skript.** Init-Skripte laufen **nur** beim allerersten Start auf ein
leeres Volume. Liegt bereits ein Volume von früher, läuft kein Skript mehr, und die Tabelle fehlt
stillschweigend. Der Dienst wäre dann von einem Zustand abhängig, den niemand sieht. Legt er die
Tabelle selbst an, läuft der Stack aus jedem Zustand hoch. Das ist genau die Anforderung aus
Szenario S2.

**Warum nicht Flyway.** Ein Werkzeug mehr, ein Namensschema mehr, ein Konzept mehr. Bei einer
Tabelle lohnt das nicht. Sobald es Änderungen an bestehenden Spalten gibt, ist Flyway die Antwort.

### 4.3 Queues, Exchange und Bindung

| Objekt | Art | Eigenschaften | Warum |
|---|---|---|---|
| `chat.messages` | Fanout-Exchange | dauerhaft, nicht selbstlöschend | Verteilt jede Nachricht an alle gebundenen Queues. Wird vom `chat-service` deklariert, der `batch-writer` deklariert ihn identisch mit, damit er auch allein starten kann |
| `chat.persist` | Quorum-Queue, dauerhaft | `x-delivery-limit: 20`, Dead-Letter nach `chat.dlq` | Hier darf nichts verloren gehen. Dauerhaft heisst: übersteht den Neustart des Brokers. Die Zustellgrenze ist das letzte Netz gegen eine Nachricht, die ewig kreist |
| `chat.dlq` | Queue, dauerhaft | keine Grenze, keine Ablaufzeit | Endstation für Nachrichten, die sich nie schreiben lassen. Hier soll man sie in Ruhe anschauen können, also wird nichts weggeworfen |
| Bindung | `chat.messages` nach `chat.persist` | Routing-Key leer | Ein Fanout-Exchange schaut den Schlüssel nicht an |

Die Live-Queue des `chat-service` und die Bot-Queue hängen später am selben Exchange. Der
`batch-writer` weiss nichts von ihnen, und sie nichts von ihm. Das ist der Sinn des Fanout.

### 4.4 Umgebungsvariablen

Alle Werte kommen aus der Umgebung. Im Repo liegt `.env.example` als einzige Vorlage, die
echte `.env` ist in `.gitignore` und wird nie eingecheckt.

| Variable | Beispielwert | Wofür |
|---|---|---|
| `POSTGRES_DB` | `chat` | Name der Datenbank, von Postgres und vom Dienst gelesen |
| `POSTGRES_USER` | `chat` | Benutzer |
| `POSTGRES_PASSWORD` | `chat` | Passwort |
| `RABBITMQ_DEFAULT_USER` | `chat` | Broker-Benutzer |
| `RABBITMQ_DEFAULT_PASS` | `chat` | Broker-Passwort |
| `BATCH_WRITER_BATCH_SIZE` | `500` | Nachrichten pro Paket |
| `BATCH_WRITER_BATCH_TIMEOUT_MS` | `200` | Zeitlimit, bis ein unvolles Paket geschrieben wird |
| `BATCH_WRITER_RETRY_BACKOFF_MS` | `5000` | Pause nach einem Datenbankfehler, siehe 3.3 |

Die Adressen der Nachbarn sind keine Variablen, sondern die Dienstnamen aus `docker-compose.yml`:
`postgres` und `rabbitmq`. Das übernimmt der interne DNS von Docker.

---

## 5. Abnahmekriterien

Jedes Kriterium ist mit einem Befehl messbar. `$DB` steht für den Wert von `POSTGRES_DB`,
`$USER` für `POSTGRES_USER`.

### A1 Alle Tests laufen in einem Lauf grün (S1)

```bash
mvn clean test
```

Erwartet: `BUILD SUCCESS`, kein übersprungener Test. Die Tests starten ihre eigene Datenbank und
ihren eigenen Broker über Testcontainers und brauchen deshalb **keinen** laufenden Stack.

### A2 Frischer Klon fährt hoch, kein Port nach aussen (S2)

```bash
cp .env.example .env
docker compose up -d --build
docker compose ps
```

Erwartet: alle Dienste `running`. In der Spalte `PORTS` steht bei **keinem** Dienst eine
Zuordnung der Form `0.0.0.0:...`. Gegenprobe vom Host aus:

```bash
curl -s -o /dev/null -w "%{http_code}" http://localhost:5432
```

Erwartet: kein Verbindungsaufbau.

### A3 Tausend Nachrichten sind binnen sechzig Sekunden geschrieben (S3)

```bash
docker compose exec postgres psql -U $USER -d $DB -c "SELECT count(*) FROM message;"
docker compose exec rabbitmq rabbitmqctl list_queues name messages
```

Erwartet: die Zählung steigt um tausend, und `chat.persist` steht danach bei null.

### A4 Nachholen nach Stillstand, mit höchstens hundert Transaktionen (S4)

Vor dem Senden den Zähler der Datenbank ablesen, nach dem Nachholen erneut:

```bash
docker compose exec postgres psql -U $USER -d $DB -c "SELECT xact_commit FROM pg_stat_database WHERE datname = '$DB';"
```

Erwartet: tausend neue Zeilen, und die Differenz der Commits liegt unter hundert.

### A5 Dublette erzeugt genau eine Zeile (S5)

Dieselbe Nachricht zweimal direkt in die Queue legen, nur mit `content_type: application/json`:

```bash
docker compose exec rabbitmq rabbitmqadmin publish routing_key=chat.persist \
  properties='{"content_type":"application/json"}' \
  payload='{"id":"cafe0000-0000-0000-0000-000000000001","roomId":"11111111-1111-1111-1111-111111111111","sender":"lernende1","text":"Dublette","sentAt":"2026-10-01T10:00:00Z"}'
```

Prüfung:

```bash
docker compose exec postgres psql -U $USER -d $DB -c "SELECT count(*) FROM message WHERE id = 'cafe0000-0000-0000-0000-000000000001';"
docker compose exec rabbitmq rabbitmqctl list_queues name messages
```

Erwartet: Zählung ist eins, und `chat.dlq` steht bei null.

### A6 Zwei Instanzen teilen sich die Queue (S6)

```bash
docker compose up -d --scale batch-writer=2
docker compose exec rabbitmq rabbitmqctl list_queues name consumers
```

Erwartet: `chat.persist` hat zwei Verbraucher. Nach tausend Nachrichten sind tausend Zeilen da,
keine doppelt:

```bash
docker compose exec postgres psql -U $USER -d $DB -c "SELECT count(*), count(DISTINCT id) FROM message;"
```

Erwartet: beide Zahlen gleich.

### A7 Datenbankausfall ohne Verlust und ohne Neustart von Hand (S7)

```bash
docker compose stop postgres
# 300 Nachrichten senden
sleep 15
docker compose start postgres
```

Erwartet: innerhalb von neunzig Sekunden stehen alle dreihundert in der Tabelle. Der Container
`batch-writer` wurde dabei **nicht** angefasst:

```bash
docker compose ps batch-writer
```

Erwartet: Spalte `STATUS` zeigt eine Laufzeit, die älter ist als der Ausfall, also keinen Neustart.

### A8 Der Quelltext hält sich an die Regeln (S8)

```bash
grep -rn "\.stream()\|\.map(\|Collectors\." batch-writer/src/main/java
git ls-files | grep -x ".env"
```

Erwartet: beide Suchen finden nichts. Zusätzlich hat jede Klasse und jede Methode in
`batch-writer/src/main/java` einen Kommentar darüber, der sagt, was sie tut und warum es sie gibt.

---

## 6. Verworfene Varianten

| Verworfen | Grund |
|---|---|
| Jede Nachricht einzeln schreiben | Szenario S4 erlaubt für tausend Nachrichten höchstens hundert Transaktionen. Einzelschreiben wäre zehnmal zu viel |
| Zuerst bestätigen, dann schreiben | Stürzt der Dienst dazwischen ab, ist die Nachricht nirgends mehr: aus der Queue gelöscht, nie geschrieben, und keine Fehlermeldung. Ein Loch im Verlauf ist schlimmer als eine Dublette |
| Die Datenbank vergibt die `id` | Macht Wiederholen unmöglich, siehe 3.2 |
| Typgesteuerter Wandler mit `__TypeId__` | Bindet zwei Dienste über einen Java-Klassennamen und scheitert an S5, siehe 2.3 |
| Eigene Queue pro Instanz | Dann bekäme jede Instanz jede Nachricht, und Skalieren würde die Arbeit vervielfachen statt aufteilen, siehe 3.4 |
| Fremdschlüssel von `message.room_id` auf `room.id` | Eine Nachricht kann vor ihrem Raum ankommen und würde grundlos in der DLQ landen, siehe 4.1 |
| Kaputtes Paket vollständig in die DLQ | Eine kaputte Nachricht nimmt vierhundertneunundneunzig gesunde mit, siehe 3.5 |
| Tests nur mit Attrappen | Abgabe 4 verlangt echte Queue und echte Datenbank. Attrappen würden beweisen, dass unser Code sich selbst aufruft, nicht dass er mit RabbitMQ und PostgreSQL zusammenarbeitet |
| Tests gegen den laufenden Stack | Dann müsste vor `mvn clean test` jemand den Stack starten, und es müssten Ports offen sein. Beides widerspricht S1 und S2 |
| Flyway für das Schema | Ein Werkzeug mehr für eine Tabelle, siehe 4.2 |

---

## 7. Offene Punkte

1. **Lastzahl.** Der Auftrag nennt eine andere Zahl als `PLANUNG.md`. Für die Bündelung ist das
   ohne Folge: die Werte fünfhundert und zweihundert Millisekunden sind in beiden Fällen sinnvoll,
   und sie sind einstellbar. Nachmessen unter Last steht noch aus.
2. **Pfad des Endpunkts.** Der Auftrag spricht von `POST /messages`, der `chat-service` bietet
   `POST /api/messages`. Der Dienst wird beide Pfade annehmen, damit die Messung in jedem Fall
   greift.
3. **Paketgrösse gegen Zustellgrenze.** Pause und Zustellgrenze hängen zusammen (3.3). Wer eine
   verändert, muss die Rechnung neu machen.
