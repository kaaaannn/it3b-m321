package ch.benedict.m321.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prueft, dass Spring alle Klassen zusammenbauen kann. Der Test hat absichtlich keinen
 * Rumpf: faellt beim Hochfahren irgendwo eine Bean weg oder ist eine Konfiguration
 * fehlerhaft, schlaegt er hier fehl, bevor irgendjemand die Anwendung startet.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    /**
     * Gibt Spring die Adressen der Testcontainer, bevor der Kontext startet.
     * Ohne das wuerde der Dienst die Namen "postgres" und "rabbitmq" aus der
     * application.yml verwenden, die es nur im Docker-Netz gibt - und der Hoer-Thread
     * der Live-Queue faende keinen Broker.
     */
    @DynamicPropertySource
    static void useTestContainers(DynamicPropertyRegistry registry) {
        TestInfrastructure.registerProperties(registry);
    }

    @Test
    void contextLoads() {
        // Kein Inhalt noetig - der Test besteht darin, dass @SpringBootTest oben durchlaeuft.
    }
}
