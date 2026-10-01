package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer. Dieser Dienst hat KEINE Weboberflaeche und keinen
 * offenen Port: er holt Nachrichten aus der Queue chat.persist und schreibt sie
 * gebuendelt in PostgreSQL. Er ist der einzige Dienst, der in die Tabelle message
 * schreibt (siehe docs/spec-batch-writer.md, Abschnitt 1).
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Uebergibt die Startklasse an Spring Boot. Weil es keinen Webserver gibt, haelt
     * spaeter der Hoer-Thread von Spring AMQP die Anwendung am Leben: solange er auf
     * die Queue hoert, laeuft der Dienst weiter.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
