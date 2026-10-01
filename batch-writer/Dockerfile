# Zwei Stufen: in der ersten wird gebaut, in der zweiten laeuft nur noch das Ergebnis.
# Aufbau wie beim chat-service, nur dass hier kein einziger Port auftaucht: dieser Dienst
# hat keine Schnittstelle, er hoert nur auf die Queue.
#
# Der Bau-Kontext ist das Wurzelverzeichnis des Projekts, festgelegt in docker-compose.yml.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

COPY pom.xml ./
COPY chat-service/pom.xml chat-service/
COPY batch-writer/pom.xml batch-writer/

COPY batch-writer/src batch-writer/src

# Die Tests laufen hier NICHT: sie starten selbst Container und brauchen dafuer Docker.
RUN mvn -B -q -pl batch-writer -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /build/batch-writer/target/*.jar app.jar

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
