package ch.benedict.m321.batchwriter;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.stereotype.Component;

/**
 * Hoert auf die Queue chat.persist und schreibt jedes Paket in die Datenbank.
 *
 * Diese Klasse ist die Naht zwischen Broker und Datenbank, und sie entscheidet die
 * Fehlerfaelle. Der Grundsatz dahinter: ein Fehler, der vorbeigeht, wird ausgesessen,
 * ein Fehler, der bleibt, wird beiseitegelegt.
 */
@Component
public class MessageBatchListener {

    private static final Logger log = LoggerFactory.getLogger(MessageBatchListener.class);

    private final MessagePayloadReader payloadReader;
    private final MessageWriter messageWriter;
    private final DeadLetterPublisher deadLetterPublisher;
    private final long retryBackoffMs;

    public MessageBatchListener(
            MessagePayloadReader payloadReader,
            MessageWriter messageWriter,
            DeadLetterPublisher deadLetterPublisher,
            @Value("${batch-writer.retry-backoff-ms}") long retryBackoffMs) {
        this.payloadReader = payloadReader;
        this.messageWriter = messageWriter;
        this.deadLetterPublisher = deadLetterPublisher;
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * Nimmt ein Paket aus der Queue, liest jede Nachricht und schreibt alle gemeinsam.
     *
     * Wichtig: diese Methode wird erst verlassen, wenn das Schreiben fertig ist. Erst
     * danach bestaetigt der Behaelter dem Broker das ganze Paket. Wirft die Methode eine
     * Ausnahme, wird nichts bestaetigt und dasselbe Paket kommt erneut.
     */
    @RabbitListener(
            queues = RabbitConfiguration.PERSIST_QUEUE_NAME,
            containerFactory = "batchListenerContainerFactory")
    public void receiveBatch(List<Message> batch) {
        log.info("Paket mit {} Nachrichten aus der Queue {} erhalten",
                batch.size(), RabbitConfiguration.PERSIST_QUEUE_NAME);

        List<IncomingMessage> readable = readAll(batch);

        writeOrHandleFailure(readable);
    }

    /**
     * Liest jede Nachricht des Pakets. Eine Nachricht, die sich nicht lesen laesst, ist
     * dauerhaft kaputt: sie geht sofort in die DLQ und wird damit als erledigt behandelt.
     * Sie darf die gesunden Nachrichten desselben Pakets nicht aufhalten.
     */
    private List<IncomingMessage> readAll(List<Message> batch) {
        List<IncomingMessage> readable = new ArrayList<>();

        for (Message amqpMessage : batch) {
            try {
                IncomingMessage message = payloadReader.read(amqpMessage.getBody());
                readable.add(message);
            } catch (InvalidMessageException fehler) {
                deadLetterPublisher.publishRawBody(amqpMessage.getBody(), fehler.getMessage());
            }
        }

        return readable;
    }

    /**
     * Schreibt das Paket und behandelt den Fehlerfall. Hier wird zwischen zwei Arten von
     * Fehlern unterschieden, und diese Unterscheidung ist der Kern des Dienstes.
     */
    private void writeOrHandleFailure(List<IncomingMessage> messages) {
        try {
            messageWriter.writeBatch(messages);
        } catch (DataAccessException fehler) {
            if (isPermanent(fehler)) {
                writeOneByOne(messages);
                return;
            }

            // Vorbeigehender Fehler, meist eine nicht erreichbare Datenbank. Wir warten
            // und werfen die Ausnahme weiter: ohne Bestaetigung stellt der Broker dasselbe
            // Paket erneut zu, es geht also nichts verloren.
            pauseAfterDatabaseFailure(fehler);
            throw fehler;
        }
    }

    /**
     * Schreibt das Paket ein einziges Mal Zeile fuer Zeile.
     *
     * Es gibt diesen Weg, damit eine einzige kaputte Nachricht nicht vierhundert-
     * neunundneunzig gesunde mitnimmt. Jede Zeile, die fuer sich gelingt, ist gerettet.
     * Nur die Zeile, die auch allein scheitert, geht in die DLQ.
     */
    private void writeOneByOne(List<IncomingMessage> messages) {
        log.warn("Paket scheitert an einem Datenfehler. Schreibe {} Nachrichten einzeln, "
                + "damit nur die kaputte Nachricht verloren geht", messages.size());

        for (IncomingMessage message : messages) {
            writeSingle(message);
        }
    }

    /**
     * Schreibt eine einzelne Nachricht. Scheitert sie dauerhaft, geht sie in die DLQ.
     * Scheitert sie an der Verbindung, wird wie oben gewartet und weitergeworfen - dann
     * bekommt der Dienst das ganze Paket erneut, was dank der Dublettenfreiheit
     * gefahrlos ist.
     */
    private void writeSingle(IncomingMessage message) {
        try {
            messageWriter.writeBatch(List.of(message));
        } catch (DataAccessException fehler) {
            if (isPermanent(fehler)) {
                log.warn("Nachricht {} laesst sich nie schreiben: {}",
                        message.id(), fehler.getMessage());
                deadLetterPublisher.publishMessage(message, fehler.getMessage());
                return;
            }

            pauseAfterDatabaseFailure(fehler);
            throw fehler;
        }
    }

    /**
     * Entscheidet, ob ein Fehler dauerhaft ist, also ob Wiederholen jemals helfen wuerde.
     *
     * Dauerhaft sind Fehler an den Daten selbst: ein Wert verletzt eine Regel der Tabelle,
     * oder das SQL passt nicht zum Schema. Alles andere gilt hier als vorbeigehend.
     *
     * Die Richtung dieser Entscheidung ist mit Absicht so gewaehlt: im Zweifel wiederholen.
     * Ein Wiederholen ist dank ON CONFLICT DO NOTHING harmlos, eine Nachricht faelschlich
     * in die DLQ zu legen ist es nicht - dann fehlt sie im Verlauf.
     */
    private boolean isPermanent(DataAccessException fehler) {
        if (fehler instanceof DataIntegrityViolationException) {
            return true;
        }
        if (fehler instanceof BadSqlGrammarException) {
            return true;
        }
        return false;
    }

    /**
     * Wartet nach einem Datenbankfehler, bevor dasselbe Paket erneut versucht wird.
     *
     * Ohne diese Pause entstuende eine heisse Schleife: der Broker stellt sofort wieder zu,
     * der Versuch scheitert sofort wieder, und der Dienst belastet Broker und Datenbank im
     * Millisekundentakt, ohne Fortschritt. Dabei belegt er ausserdem alle Verbindungen des
     * Pools, so dass auch andere nicht mehr durchkommen.
     *
     * Die Pause ist zugleich der Rueckstau nach hinten: solange sie laeuft, holt dieser
     * Dienst nichts Neues, und die Queue waechst sichtbar. Das ist eine Warnung, kein Fehler.
     */
    private void pauseAfterDatabaseFailure(DataAccessException fehler) {
        log.warn("Datenbank nicht erreichbar ({}). Warte {} ms und versuche dasselbe Paket erneut",
                fehler.getClass().getSimpleName(), retryBackoffMs);

        try {
            Thread.sleep(retryBackoffMs);
        } catch (InterruptedException unterbrochen) {
            // Wird der Dienst beim Warten beendet, merken wir uns die Unterbrechung und
            // geben sie weiter, statt sie zu verschlucken. Sonst liefe der Thread weiter,
            // obwohl er aufhoeren soll.
            Thread.currentThread().interrupt();
        }
    }
}
