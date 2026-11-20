package ch.benedict.m321.chat;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Startet fuer die Tests eine echte Datenbank und einen echten Broker in Containern.
 * Es gibt diese Klasse, damit nicht jeder Test seine eigenen Container hochfahren muss:
 * das kostete pro Testklasse mehrere Sekunden. Vorlage ist dieselbe Klasse im batch-writer.
 *
 * Seit es im chat-service einen @RabbitListener gibt, braucht jeder @SpringBootTest einen
 * Broker: der Hoer-Behaelter verbindet sich gleich beim Start und versucht es sonst
 * endlos weiter. Darum melden sich alle @SpringBootTest-Klassen hier an.
 *
 * Testcontainers waehlt auf dem Host ZUFAELLIGE Ports. Deshalb laufen diese Tests auch
 * dann, wenn im docker-compose.yml kein einziger Port veroeffentlicht ist (S1 und S2).
 */
public final class TestInfrastructure {

    /** Dieselben Images wie im Stack, damit wir nicht gegen eine andere Version testen. */
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");
    private static final DockerImageName RABBIT_IMAGE = DockerImageName.parse("rabbitmq:4-management-alpine");

    public static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE);
    public static final RabbitMQContainer RABBIT = new RabbitMQContainer(RABBIT_IMAGE);

    // Der statische Block laeuft genau einmal, wenn diese Klasse zum ersten Mal gebraucht
    // wird. Die Container bleiben danach fuer alle Testklassen stehen und werden am Ende
    // der JVM von Testcontainers wieder abgeraeumt.
    static {
        POSTGRES.start();
        RABBIT.start();
    }

    /** Niemand soll diese Klasse versehentlich instanzieren, sie hat nur statische Teile. */
    private TestInfrastructure() {
    }

    /**
     * Schreibt die Adressen der gestarteten Container in die Spring-Einstellungen.
     * Jeder Test ruft das aus seiner eigenen Methode mit @DynamicPropertySource auf.
     * "Dynamic" heisst: die Werte stehen erst zur Laufzeit fest, weil die Ports zufaellig sind.
     */
    public static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }
}
