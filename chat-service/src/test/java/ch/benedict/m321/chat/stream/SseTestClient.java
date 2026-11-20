package ch.benedict.m321.chat.stream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Ein kleiner Stream-Client fuer die Tests: oeffnet GET /stream?roomId=... auf dem echten
 * Port der Anwendung und sammelt die "id:"-Zeilen aller Ereignisse, die ankommen.
 *
 * Es gibt diese Klasse, weil MockMvc den Inhalt eines offenen Streams nicht lesen kann
 * (siehe Schritt 1). Hier laeuft eine echte TCP-Verbindung, wie bei curl -N.
 *
 * Die Anfrage wird absichtlich von Hand ueber einen Socket geschrieben, nicht mit
 * HttpURLConnection. Grund: HttpURLConnection.disconnect() wartet, bis der Lese-Thread
 * fertig gelesen hat - bei einem Stream, der nie endet, also ewig. Einen Socket dagegen
 * kann man jederzeit von aussen schliessen, und der Lese-Thread bekommt sofort eine
 * IOException.
 */
class SseTestClient {

    private final Socket socket;
    private final BufferedReader lineReader;

    /**
     * Die IDs der empfangenen Ereignisse, in Ankunftsreihenfolge. Eine BlockingQueue,
     * weil zwei Threads darauf zugreifen: der Lese-Thread legt hinein, der Test nimmt
     * heraus und darf dabei eine Weile warten.
     */
    private final LinkedBlockingQueue<String> receivedIds = new LinkedBlockingQueue<>();

    /**
     * Oeffnet die Verbindung, prueft die Antwort und startet den Lese-Thread.
     *
     * Der Konstruktor wartet, bis die Kopfzeilen der Antwort da sind. Die schickt der
     * Server erst mit der Kommentarzeile ": verbunden", und die schreibt er erst, nachdem
     * der Controller die Verbindung in der StreamRegistry angemeldet hat. Kehrt der
     * Konstruktor zurueck, ist die Verbindung also sicher angemeldet.
     */
    SseTestClient(int port, UUID roomId) throws IOException {
        this(port, roomId, null);
    }

    /**
     * Wie oben, aber als Neuverbindung: Die Anfrage traegt die Kopfzeile Last-Event-ID,
     * so wie ein Browser sie nach einem Abbruch von selbst mitschickt. Ist lastEventId
     * null, fehlt die Kopfzeile.
     */
    SseTestClient(int port, UUID roomId, String lastEventId) throws IOException {
        socket = new Socket("localhost", port);
        sendRequest(roomId, lastEventId);

        InputStream input = socket.getInputStream();
        InputStreamReader inputReader = new InputStreamReader(input, StandardCharsets.UTF_8);
        lineReader = new BufferedReader(inputReader);
        skipResponseHead(roomId);

        // Ein eigener Thread, weil das Lesen blockiert, bis die naechste Zeile kommt.
        // Als Daemon haelt er die JVM am Ende der Tests nicht am Leben.
        Thread reader = new Thread(this::readEvents, "sse-test-client-" + roomId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Wartet hoechstens timeoutMs Millisekunden auf das naechste Ereignis und gibt dessen
     * ID zurueck, oder null, wenn in dieser Zeit keines kam.
     */
    String nextId(long timeoutMs) throws InterruptedException {
        return receivedIds.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * Schliesst die Verbindung. Der Lese-Thread bekommt dadurch eine IOException und endet,
     * und der Server merkt beim naechsten Schreiben, dass der Client weg ist.
     */
    void close() throws IOException {
        socket.close();
    }

    /**
     * Schreibt die GET-Anfrage. HTTP/1.0 statt 1.1, damit der Server den Rumpf ohne
     * "chunked"-Zerlegung schickt: dann kommen die SSE-Zeilen genau so an, wie sie im
     * Stream stehen, und koennen direkt Zeile fuer Zeile gelesen werden.
     */
    private void sendRequest(UUID roomId, String lastEventId) throws IOException {
        String request = "GET /stream?roomId=" + roomId + " HTTP/1.0\r\n"
                + "Host: localhost\r\n"
                + "Accept: text/event-stream\r\n";
        if (lastEventId != null) {
            request = request + "Last-Event-ID: " + lastEventId + "\r\n";
        }
        // Die Leerzeile beendet die Kopfzeilen, danach erwartet der Server keine mehr.
        request = request + "\r\n";

        OutputStream output = socket.getOutputStream();
        output.write(request.getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    /**
     * Liest die Statuszeile und die Kopfzeilen bis zur Leerzeile. Danach beginnt der Stream.
     * Ist der Status nicht 200, bricht der Test hier mit einer klaren Meldung ab.
     */
    private void skipResponseHead(UUID roomId) throws IOException {
        String statusLine = lineReader.readLine();
        if (statusLine == null || !statusLine.contains(" 200 ")) {
            throw new IllegalStateException("Stream fuer Raum " + roomId + " antwortet mit: " + statusLine);
        }

        String headerLine = lineReader.readLine();
        while (headerLine != null && !headerLine.isEmpty()) {
            headerLine = lineReader.readLine();
        }
    }

    /**
     * Liest den Stream Zeile fuer Zeile, bis die Verbindung zu ist. Ein SSE-Ereignis
     * besteht aus Zeilen wie "id:..." und "data:...", getrennt durch eine Leerzeile;
     * Kommentarzeilen beginnen mit ":". Fuer die Tests zaehlt nur die "id:"-Zeile.
     */
    private void readEvents() {
        try {
            String line = lineReader.readLine();
            while (line != null) {
                if (line.startsWith("id:")) {
                    String id = line.substring("id:".length()).trim();
                    receivedIds.add(id);
                }
                line = lineReader.readLine();
            }
        } catch (IOException verbindungZu) {
            // Erwartet: close() schliesst den Socket mitten im Lesen.
        }
    }
}
