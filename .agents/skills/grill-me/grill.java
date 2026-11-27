// grill.java - Helfer fuer den Skill grill-me. Keine Abhaengigkeiten, laeuft mit JDK 21:
//   java .agents/skills/grill-me/grill.java start <email> <abgabe> [--probe] [--tool <name>]
//   java .agents/skills/grill-me/grill.java wait
//   java .agents/skills/grill-me/grill.java entry <datei>
//   java .agents/skills/grill-me/grill.java submit <datei>
//   java .agents/skills/grill-me/grill.java selftest
// Der Token steht nur in .grill/session.json und wird nie ausgegeben.

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class grill {

    // Die fuenf Dateien, deren Pruefsumme der Server vergleicht.
    static final String[] SKILL_PFADE = {
        ".agents/skills/grill-me/SKILL.md",
        ".agents/skills/grill-me/grill.java",
        ".claude/skills/grill-me/SKILL.md",
        ".claude/rules/grill-regeln.md",
        "AGENTS.md",
    };
    static final String STANDARD_URL = "https://grill-me.pritz-it.com";
    static final List<String> WERKZEUGE = List.of("claude-code", "antigravity", "opencode");
    static final int POLL_SEKUNDEN = 5;
    static final int POLL_MAXIMAL_SEKUNDEN = 600;
    // Ab dieser Ueberschrift gehoert der Text nur in die lokale Datei, nicht in /api/protocols.
    static final String EINTRAEGE_UEBERSCHRIFT = "## Einträge";

    // Fehler, der als "FEHLER <CODE>: <Text>" ausgegeben wird.
    static class GrillFehler extends Exception {
        final String code;

        GrillFehler(String code, String text) {
            super(text);
            this.code = code;
        }
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("FEHLER BEFEHL: erwartet start, wait, entry, submit oder selftest");
            System.exit(1);
        }
        try {
            switch (args[0]) {
                case "selftest" -> selftest();
                case "start" -> start(args);
                case "wait" -> warten();
                case "entry" -> eintrag(args);
                case "submit" -> abgeben(args);
                default -> throw new GrillFehler("BEFEHL", "Unbekannter Befehl «" + args[0] + "»");
            }
        } catch (GrillFehler fehler) {
            System.out.println("FEHLER " + fehler.code + ": " + fehler.getMessage());
            System.exit(1);
        } catch (Exception unerwartet) {
            System.out.println("FEHLER INTERN: " + unerwartet);
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- Befehle

    // Legt eine Sitzung an. Der Server schickt danach die Bestaetigungsmail.
    static void start(String[] args) throws Exception {
        if (args.length < 3) {
            throw new GrillFehler("BEFEHL", "Aufruf: start <email> <abgabe> [--probe] [--tool <name>]");
        }
        String email = args[1].trim();
        int abgabe;
        try {
            abgabe = Integer.parseInt(args[2]);
        } catch (NumberFormatException e) {
            throw new GrillFehler("BEFEHL", "Die Abgabe muss eine Zahl sein (1 oder 2).");
        }
        boolean probe = false;
        String werkzeug = "claude-code";
        for (int i = 3; i < args.length; i++) {
            if (args[i].equals("--probe")) {
                probe = true;
            } else if (args[i].equals("--tool") && i + 1 < args.length) {
                werkzeug = args[++i];
            } else {
                throw new GrillFehler("BEFEHL", "Unbekannte Option «" + args[i] + "»");
            }
        }
        if (!WERKZEUGE.contains(werkzeug)) {
            throw new GrillFehler("BEFEHL", "--tool muss claude-code, antigravity oder opencode sein");
        }

        Path wurzel = repoWurzel();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("email", email);
        body.put("handin", (long) abgabe);
        body.put("dry_run", probe);
        body.put("commit", commit(wurzel, abgabe));
        body.put("tool", werkzeug);
        body.put("checksums", pruefsummen(wurzel));

        Map<String, Object> json = post("/api/sessions", null, jsonObjekt(body)).json();
        Map<String, Object> sitzung = new LinkedHashMap<>();
        sitzung.put("session_id", json.get("session_id"));
        sitzung.put("poll_secret", json.get("poll_secret"));
        sitzung.put("base_url", basisUrl());
        sitzung.put("handin", (long) abgabe);
        sitzung.put("dry_run", probe);
        sitzungSchreiben(wurzel, sitzung);
        System.out.println("OK Mail an " + email + " gesendet");
    }

    // Fragt alle 5 Sekunden nach, bis die Person den Link bestaetigt hat.
    static void warten() throws Exception {
        Path wurzel = repoWurzel();
        Map<String, Object> sitzung = sitzungLesen(wurzel);
        String id = text(sitzung, "session_id");
        String geheimnis = text(sitzung, "poll_secret");
        if (id.isEmpty() || geheimnis.isEmpty()) {
            throw new GrillFehler("KEINE_SITZUNG", "Es gibt keine Sitzung, starte zuerst mit «start».");
        }
        long ende = System.currentTimeMillis() + POLL_MAXIMAL_SEKUNDEN * 1000L;
        while (System.currentTimeMillis() < ende) {
            Map<String, Object> json;
            try {
                json = get("/api/sessions/" + id, geheimnis).json();
            } catch (GrillFehler fehler) {
                if (!fehler.code.equals("NETZ")) {
                    throw fehler;
                }
                // Kurze Netzstoerung: weiter versuchen, bis die 10 Minuten um sind.
                pause(POLL_SEKUNDEN);
                continue;
            }
            String status = text(json, "status");
            if (status.equals("ready")) {
                // Der Server liefert den Token nur dieses eine Mal: sofort wegschreiben.
                // Kommt keiner mehr (zweiter Aufruf von wait), bleibt der schon gespeicherte bestehen.
                String neuerToken = text(json, "token");
                if (!neuerToken.isEmpty()) {
                    sitzung.put("token", neuerToken);
                    sitzungSchreiben(wurzel, sitzung);
                } else if (text(sitzung, "token").isEmpty()) {
                    throw new GrillFehler("TOKEN_VERLOREN", "Der Token wurde schon abgeholt und ist nicht mehr vorhanden, dieser Lauf kann nicht weitergehen: starte einen neuen mit «start».");
                }
                System.out.println("OK bereit");
                return;
            }
            if (status.equals("expired")) {
                throw new GrillFehler("SITZUNG_ABGELAUFEN", "Der Link wurde nicht rechtzeitig bestätigt, starte neu.");
            }
            pause(POLL_SEKUNDEN);
        }
        throw new GrillFehler("ZEITUEBERSCHREITUNG", "Der Link wurde in 10 Minuten nicht bestätigt, starte neu.");
    }

    // Sendet genau einen Eintrag. Der Server haengt ihn an, danach ist er fest.
    static void eintrag(String[] args) throws Exception {
        if (args.length < 2) {
            throw new GrillFehler("BEFEHL", "Aufruf: entry <datei>");
        }
        Path wurzel = repoWurzel();
        Map<String, Object> sitzung = sitzungLesen(wurzel);
        String token = token(sitzung);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("markdown", dateiLesen(wurzel, args[1]));
        Antwort antwort;
        try {
            antwort = post("/api/entries", token, jsonObjekt(body));
        } catch (GrillFehler fehler) {
            if (fehler.code.equals("NETZ")) {
                // Der Eintrag ist vielleicht trotzdem angekommen: merken, damit ein Sprung der Nummer
                // nach dem erneuten Senden nicht als Lücke gilt.
                long bisher = sitzung.get("entry_failures") instanceof Long l ? l : 0;
                sitzung.put("entry_failures", bisher + 1);
                sitzungSchreiben(wurzel, sitzung);
            }
            throw fehler;
        }
        Object seq = antwort.json().get("seq");
        if (seq instanceof Long nummer) {
            Object letzte = sitzung.get("last_seq");
            long fehlversuche = sitzung.get("entry_failures") instanceof Long l ? l : 0;
            String meldung = sprungMeldung(letzte instanceof Long l ? l : null, nummer, fehlversuche);
            if (meldung != null) {
                System.out.println(meldung);
            }
            sitzung.put("last_seq", nummer);
            sitzung.remove("entry_failures");
            sitzungSchreiben(wurzel, sitzung);
        }
        System.out.println("OK Eintrag " + (seq instanceof Long nummer ? nummer.toString() : "?"));
    }

    // Springt die Eintragsnummer um mehr als 1, fehlt ein Eintrag (WARNUNG). Gehen die Sprünge
    // aber höchstens auf eigene gescheiterte Sendeversuche zurück, ist es wohl ein Doppelter (HINWEIS).
    static String sprungMeldung(Long letzte, long seq, long fehlversuche) {
        long erwartet = letzte == null ? 1 : letzte + 1;
        long sprung = seq - erwartet;
        if (sprung <= 0) {
            return null;
        }
        if (sprung <= fehlversuche) {
            return "HINWEIS möglicherweise doppelt gesendet: erwartet Eintrag " + erwartet + ", erhalten " + seq;
        }
        return "WARNUNG Lücke: erwartet Eintrag " + erwartet + ", erhalten " + seq;
    }

    // Schliesst den Lauf ab: Kopf und Summe plus die Pruefsummen von jetzt.
    static void abgeben(String[] args) throws Exception {
        if (args.length < 2) {
            throw new GrillFehler("BEFEHL", "Aufruf: submit <datei>");
        }
        Path wurzel = repoWurzel();
        Map<String, Object> sitzung = sitzungLesen(wurzel);
        String token = token(sitzung);
        String markdown = dateiLesen(wurzel, args[1]);
        // Die Einträge stehen nur in der lokalen Datei; der Server hat sie schon einzeln bekommen.
        int schnitt = markdown.indexOf("\n" + EINTRAEGE_UEBERSCHRIFT);
        if (schnitt >= 0) {
            markdown = markdown.substring(0, schnitt + 1);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("markdown", markdown);
        body.put("checksums", pruefsummen(wurzel));
        // Merken, dass es einen Versuch gab: kommt danach TOKEN_USED, ist der erste Versuch angekommen.
        boolean fruehererVersuch = Boolean.TRUE.equals(sitzung.get("submit_versucht"));
        sitzung.put("submit_versucht", true);
        sitzungSchreiben(wurzel, sitzung);
        Map<String, Object> antwort;
        try {
            antwort = post("/api/protocols", token, jsonObjekt(body)).json();
        } catch (GrillFehler fehler) {
            if (fehler.code.equals("TOKEN_USED") && fruehererVersuch) {
                sitzung.remove("token");
                sitzungSchreiben(wurzel, sitzung);
                System.out.println("OK abgegeben (bereits angenommen)");
                return;
            }
            throw fehler;
        }

        sitzung.remove("token");
        sitzungSchreiben(wurzel, sitzung);
        if ("SKILL_CHANGED_DURING_RUN".equals(antwort.get("code"))) {
            System.out.println("OK abgegeben, aber SKILL_CHANGED_DURING_RUN");
        } else {
            System.out.println("OK abgegeben");
        }
    }

    // ---------------------------------------------------------------- Git und Dateien

    static String git(Path ordner, String... argumente) throws Exception {
        List<String> befehl = new ArrayList<>();
        befehl.add("git");
        befehl.addAll(List.of(argumente));
        Process prozess = new ProcessBuilder(befehl).directory(ordner.toFile()).redirectErrorStream(true).start();
        String ausgabe = new String(prozess.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int rueckgabe = prozess.waitFor();
        if (rueckgabe != 0) {
            throw new GrillFehler("GIT", "git " + String.join(" ", argumente) + " ist fehlgeschlagen: " + ausgabe);
        }
        return ausgabe;
    }

    // Wurzel des Kursrepos, egal aus welchem Unterordner der Helfer gestartet wird.
    static Path repoWurzel() throws Exception {
        return Path.of(git(Path.of("").toAbsolutePath(), "rev-parse", "--show-toplevel"));
    }

    // Commit des Tags bewertung-<n>, sonst HEAD.
    static String commit(Path wurzel, int abgabe) throws Exception {
        try {
            return git(wurzel, "rev-parse", "--short", "bewertung-" + abgabe + "^{commit}");
        } catch (GrillFehler keinTag) {
            return git(wurzel, "rev-parse", "--short", "HEAD");
        }
    }

    static Map<String, String> pruefsummen(Path wurzel) throws IOException {
        Map<String, String> summen = new LinkedHashMap<>();
        for (String pfad : SKILL_PFADE) {
            Path datei = wurzel.resolve(pfad);
            // Eine fehlende Datei wird weggelassen: der Server wertet das als SKILL_MODIFIED.
            if (Files.exists(datei)) {
                summen.put(pfad, checksum(Files.readAllBytes(datei)));
            }
        }
        return summen;
    }

    // SHA-256 in Kleinbuchstaben-Hex, nachdem jedes CRLF zu LF wurde (gleiche Summe auf Windows).
    static String checksum(byte[] inhalt) {
        // ISO_8859_1 bildet jedes Byte auf genau ein Zeichen ab, das Ersetzen veraendert also nur CRLF.
        String text = new String(inhalt, StandardCharsets.ISO_8859_1).replace("\r\n", "\n");
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Relative Pfade gelten ab der Repo-Wurzel, egal in welchem Unterordner der Helfer läuft.
    static String dateiLesen(Path wurzel, String pfad) throws GrillFehler, IOException {
        Path datei = wurzel.resolve(pfad);
        if (!Files.exists(datei)) {
            throw new GrillFehler("DATEI", "Die Datei «" + pfad + "» gibt es nicht.");
        }
        return Files.readString(datei, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- Sitzungsdatei

    static Path sitzungsDatei(Path wurzel) {
        return wurzel.resolve(".grill").resolve("session.json");
    }

    static void sitzungSchreiben(Path wurzel, Map<String, Object> sitzung) throws IOException {
        Path datei = sitzungsDatei(wurzel);
        Files.createDirectories(datei.getParent());
        // Die Datei zuerst mit rw------- anlegen, erst dann Geheimnisse hineinschreiben.
        if (!Files.exists(datei)) {
            try {
                Files.createFile(datei, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException windows) {
                // Windows kennt keine POSIX-Rechte, der Ordner liegt im eigenen Repo.
                Files.createFile(datei);
            }
        }
        Files.writeString(datei, jsonObjekt(sitzung), StandardCharsets.UTF_8);
    }

    static Map<String, Object> sitzungLesen(Path wurzel) throws Exception {
        Path datei = sitzungsDatei(wurzel);
        if (!Files.exists(datei)) {
            throw new GrillFehler("KEINE_SITZUNG", "Es gibt keine Sitzung, starte zuerst mit «start».");
        }
        return new JsonLeser(Files.readString(datei, StandardCharsets.UTF_8)).objekt();
    }

    static String token(Map<String, Object> sitzung) throws GrillFehler {
        String token = text(sitzung, "token");
        if (token.isEmpty()) {
            throw new GrillFehler("KEIN_TOKEN", "Es gibt keinen gültigen Token, starte mit «start» und «wait» neu.");
        }
        return token;
    }

    static String text(Map<String, Object> json, String schluessel) {
        return json.get(schluessel) instanceof String s ? s : "";
    }

    // ---------------------------------------------------------------- HTTP

    record Antwort(int status, String body) {
        Map<String, Object> json() throws GrillFehler {
            if (body.isBlank()) {
                return new LinkedHashMap<>();
            }
            try {
                return new JsonLeser(body).objekt();
            } catch (RuntimeException e) {
                throw new GrillFehler("ANTWORT", "Der Server hat keine lesbare Antwort geschickt.");
            }
        }
    }

    static String basisUrl() {
        String url = System.getenv("GRILL_BASE_URL");
        String basis = (url == null || url.isBlank()) ? STANDARD_URL : url.trim();
        return basis.endsWith("/") ? basis.substring(0, basis.length() - 1) : basis;
    }

    static Antwort get(String pfad, String pollGeheimnis) throws GrillFehler {
        HttpRequest anfrage = HttpRequest.newBuilder(URI.create(basisUrl() + pfad))
            .timeout(Duration.ofSeconds(20))
            .header("X-Poll-Secret", pollGeheimnis)
            .GET()
            .build();
        return senden(anfrage);
    }

    // token ist null beim Start (noch keiner da), sonst der Bearer-Token der Sitzung.
    static Antwort post(String pfad, String token, String json) throws GrillFehler {
        HttpRequest.Builder anfrage = HttpRequest.newBuilder(URI.create(basisUrl() + pfad))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (token != null) {
            anfrage.header("Authorization", "Bearer " + token);
        }
        return senden(anfrage.build());
    }

    // Jeder 2xx-Status gilt als Erfolg; alles andere wird zu «FEHLER <CODE>: <Text>».
    static Antwort senden(HttpRequest anfrage) throws GrillFehler {
        HttpResponse<String> antwort;
        try {
            antwort = HttpClient.newHttpClient().send(anfrage, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new GrillFehler("NETZ", "Der Server ist nicht erreichbar, prüfe die Verbindung und versuche es erneut.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GrillFehler("NETZ", "Die Anfrage wurde unterbrochen.");
        }
        if (antwort.statusCode() / 100 == 2) {
            return new Antwort(antwort.statusCode(), antwort.body());
        }
        throw fehlerAus(antwort.statusCode(), antwort.body());
    }

    // Macht aus einer Fehlerantwort den Fehler des Helfers. Jeder 5xx-Status (mit oder ohne JSON) ist
    // wie ein Netzfehler: der Server hat die Anfrage vielleicht nicht verarbeitet, erneut senden ist erlaubt.
    static GrillFehler fehlerAus(int status, String body) {
        if (status >= 500) {
            return new GrillFehler("NETZ", "Der Server hat gerade ein Problem (Status " + status + "), versuche es erneut.");
        }
        Map<String, Object> fehler;
        try {
            fehler = new JsonLeser(body).objekt();
        } catch (RuntimeException keinJson) {
            return new GrillFehler("HTTP_" + status, "Der Server hat mit Status " + status + " geantwortet.");
        }
        return new GrillFehler(text(fehler, "code"), text(fehler, "message"));
    }

    static void pause(int sekunden) {
        try {
            Thread.sleep(sekunden * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- JSON (von Hand, ohne Bibliothek)

    // Schreibt einen Text als JSON-String: Anfuehrungszeichen, Backslash und Steuerzeichen werden maskiert.
    static String jsonString(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char zeichen : text.toCharArray()) {
            switch (zeichen) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (zeichen < 0x20) {
                        out.append(String.format("\\u%04x", (int) zeichen));
                    } else {
                        out.append(zeichen);
                    }
                }
            }
        }
        return out.append('"').toString();
    }

    static String jsonWert(Object wert) {
        if (wert == null) {
            return "null";
        }
        if (wert instanceof String s) {
            return jsonString(s);
        }
        if (wert instanceof Map<?, ?> map) {
            return jsonObjekt(map);
        }
        return wert.toString(); // Zahl oder Boolean
    }

    static String jsonObjekt(Map<?, ?> werte) {
        StringBuilder out = new StringBuilder("{");
        boolean erstes = true;
        for (Map.Entry<?, ?> eintrag : werte.entrySet()) {
            if (!erstes) {
                out.append(',');
            }
            erstes = false;
            out.append(jsonString(eintrag.getKey().toString())).append(':').append(jsonWert(eintrag.getValue()));
        }
        return out.append('}').toString();
    }

    // Liest ein JSON-Objekt mit Texten, Zahlen, Wahrheitswerten, null und verschachtelten Objekten.
    static class JsonLeser {
        private final String quelle;
        private int stelle = 0;

        JsonLeser(String quelle) {
            this.quelle = quelle;
        }

        Map<String, Object> objekt() {
            leerraum();
            erwarte('{');
            Map<String, Object> ergebnis = new LinkedHashMap<>();
            leerraum();
            if (vorschau() == '}') {
                stelle++;
                return ergebnis;
            }
            while (true) {
                leerraum();
                String schluessel = string();
                leerraum();
                erwarte(':');
                ergebnis.put(schluessel, wert());
                leerraum();
                char naechstes = quelle.charAt(stelle++);
                if (naechstes == '}') {
                    return ergebnis;
                }
                if (naechstes != ',') {
                    throw new IllegalArgumentException("JSON: «,» oder «}» erwartet bei " + stelle);
                }
            }
        }

        private Object wert() {
            leerraum();
            char zeichen = vorschau();
            if (zeichen == '"') {
                return string();
            }
            if (zeichen == '{') {
                return objekt();
            }
            if (quelle.startsWith("true", stelle)) {
                stelle += 4;
                return Boolean.TRUE;
            }
            if (quelle.startsWith("false", stelle)) {
                stelle += 5;
                return Boolean.FALSE;
            }
            if (quelle.startsWith("null", stelle)) {
                stelle += 4;
                return null;
            }
            int anfang = stelle;
            while (stelle < quelle.length() && "+-0123456789.eE".indexOf(quelle.charAt(stelle)) >= 0) {
                stelle++;
            }
            if (anfang == stelle) {
                throw new IllegalArgumentException("JSON: unbekannter Wert bei " + stelle);
            }
            return Long.parseLong(quelle.substring(anfang, stelle));
        }

        private String string() {
            erwarte('"');
            StringBuilder out = new StringBuilder();
            while (true) {
                char zeichen = quelle.charAt(stelle++);
                if (zeichen == '"') {
                    return out.toString();
                }
                if (zeichen != '\\') {
                    out.append(zeichen);
                    continue;
                }
                char maske = quelle.charAt(stelle++);
                switch (maske) {
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        out.append((char) Integer.parseInt(quelle.substring(stelle, stelle + 4), 16));
                        stelle += 4;
                    }
                    default -> out.append(maske); // " \ /
                }
            }
        }

        private char vorschau() {
            return quelle.charAt(stelle);
        }

        private void erwarte(char zeichen) {
            if (quelle.charAt(stelle++) != zeichen) {
                throw new IllegalArgumentException("JSON: «" + zeichen + "» erwartet bei " + (stelle - 1));
            }
        }

        private void leerraum() {
            while (stelle < quelle.length() && Character.isWhitespace(quelle.charAt(stelle))) {
                stelle++;
            }
        }
    }

    // ---------------------------------------------------------------- Selbsttest

    static void pruefe(boolean ok, String was) {
        if (!ok) {
            throw new AssertionError("selftest FAIL: " + was);
        }
    }

    static void selftest() {
        byte[] mitCrlf = "a\r\nb\n".getBytes(StandardCharsets.UTF_8);
        byte[] mitLf = "a\nb\n".getBytes(StandardCharsets.UTF_8);
        byte[] anders = "a\nc\n".getBytes(StandardCharsets.UTF_8);
        pruefe(checksum(mitCrlf).equals(checksum(mitLf)), "CRLF und LF gleich");
        pruefe(!checksum(mitLf).equals(checksum(anders)), "andere Bytes, andere Summe");
        pruefe(checksum("abc".getBytes(StandardCharsets.UTF_8)).equals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"), "SHA-256 von abc");

        pruefe(jsonString("a\"b\\c\nd").equals("\"a\\\"b\\\\c\\nd\""), "Anführungszeichen, Backslash, Zeilenumbruch");
        pruefe(jsonString("äöü «x»").equals("\"äöü «x»\""), "Umlaute bleiben");
        pruefe(jsonString("\u0001").equals("\"\\u0001\""), "Steuerzeichen");

        // Hin und zurueck: was wir schreiben, liest unser Leser wieder genau so.
        String heikel = "Zeile 1\n\"Zitat\" mit \\ und Tab\t und ä ö ü ß «Guillemets» \r\n Ende";
        Map<String, Object> eingabe = new LinkedHashMap<>();
        eingabe.put("markdown", heikel);
        eingabe.put("zahl", 3L);
        eingabe.put("ja", true);
        Map<String, Object> zurueck = new JsonLeser(jsonObjekt(eingabe)).objekt();
        pruefe(heikel.equals(zurueck.get("markdown")), "Round-Trip Text");
        pruefe(Long.valueOf(3).equals(zurueck.get("zahl")), "Round-Trip Zahl");
        pruefe(Boolean.TRUE.equals(zurueck.get("ja")), "Round-Trip Boolean");

        Map<String, Object> fehler = new JsonLeser("{\"code\":\"LIMIT_REACHED\",\"message\":\"Du hast \\\"alle\\\" Läufe verbraucht\\u0021\"}").objekt();
        pruefe("LIMIT_REACHED".equals(fehler.get("code")), "Fehlercode lesen");
        pruefe("Du hast \"alle\" Läufe verbraucht!".equals(fehler.get("message")), "Fehlertext lesen");

        // Eintragsnummern: normal, Doppelter nach Fehlversuch, echte Lücke.
        pruefe(sprungMeldung(null, 1, 0) == null, "erste Nummer 1 ist normal");
        pruefe(sprungMeldung(2L, 3, 0) == null, "lückenlos");
        pruefe(sprungMeldung(2L, 4, 0).startsWith("WARNUNG Lücke"), "Sprung ohne Fehlversuch ist Lücke");
        pruefe(sprungMeldung(2L, 4, 1).startsWith("HINWEIS möglicherweise doppelt"), "Sprung 2 nach Fehlversuch ist Hinweis");
        pruefe(sprungMeldung(2L, 5, 1).startsWith("WARNUNG Lücke"), "Sprung 3 nach einem Fehlversuch bleibt Lücke");

        // Fehlerantworten: 5xx ist immer NETZ (erneut senden erlaubt), 4xx behält den Code des Servers.
        pruefe(fehlerAus(500, "{\"code\":\"INTERNAL\",\"message\":\"x\"}").code.equals("NETZ"), "500 mit JSON ist NETZ");
        pruefe(fehlerAus(502, "<html>Bad Gateway</html>").code.equals("NETZ"), "502 ohne JSON ist NETZ");
        pruefe(fehlerAus(422, "{\"code\":\"ENTRY_INVALID\",\"message\":\"x\"}").code.equals("ENTRY_INVALID"), "422 behält Code");
        pruefe(fehlerAus(404, "nope").code.equals("HTTP_404"), "4xx ohne JSON");
        System.out.println("selftest OK");
    }
}
