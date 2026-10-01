package ch.benedict.m321.batchwriter;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, so wie sie aus der Queue kommt. Die fuenf Felder sind der Vertrag
 * mit dem chat-service (siehe docs/spec-batch-writer.md, Abschnitt 2.2).
 *
 * Dieser Datensatz ist absichtlich eine EIGENE Klasse und nicht die Message-Klasse des
 * chat-service: die zwei Dienste sollen sich keine Klasse teilen. Teilten sie eine,
 * koennte man den chat-service nicht mehr aendern, ohne den batch-writer anzufassen.
 * Verbunden sind sie nur ueber das JSON, nicht ueber Java.
 */
public record IncomingMessage(
        UUID id,
        UUID roomId,
        String sender,
        String text,
        Instant sentAt) {
}
