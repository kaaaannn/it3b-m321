package ch.benedict.m321.chat.stream;

import java.util.List;

import ch.benedict.m321.chat.message.Message;

/**
 * Das Ergebnis einer Nachhol-Anfrage an den ReplayBuffer: die verpassten Nachrichten und
 * ein Kennzeichen, ob die Last-Event-ID ueberhaupt im Puffer stand.
 *
 * Es gibt diese Klasse, weil eine leere Liste allein zwei Bedeutungen haette: "du hast
 * nichts verpasst" oder "ich kenne deine ID nicht, ich kann dir nicht sagen, was fehlt".
 * Im zweiten Fall schreibt der Controller den Kommentar ": last-event-id unbekannt",
 * damit der Client weiss, dass er den Rest aus der Datenbank holen muss (Spezifikation 3.6).
 *
 * @param lastEventIdKnown true, wenn die ID im Puffer gefunden wurde
 * @param missedMessages   die Nachrichten NACH dieser ID, in Ankunftsreihenfolge
 */
public record ReplayResult(boolean lastEventIdKnown, List<Message> missedMessages) {
}
