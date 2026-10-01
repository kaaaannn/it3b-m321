package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Prueft, dass Spring alle Klassen dieses Dienstes zusammenbauen kann.
 * Der Test hat absichtlich keinen Rumpf: faellt beim Hochfahren eine Bean weg oder ist
 * eine Konfiguration fehlerhaft, schlaegt er hier fehl, bevor der Dienst irgendwo laeuft.
 */
@SpringBootTest
class BatchWriterApplicationTest {

    /**
     * Gibt Spring die Adressen der Testcontainer, bevor der Kontext startet.
     * Ohne das wuerde der Dienst die Namen "postgres" und "rabbitmq" aus der
     * application.yml verwenden, die es nur im Docker-Netz gibt.
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
