package ch.benedict.m321.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prueft, dass Spring alle Klassen zusammenbauen kann. Der Test hat absichtlich keinen
 * Rumpf: faellt beim Hochfahren irgendwo eine Bean weg oder ist eine Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand die Anwendung startet.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
