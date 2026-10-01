# Zwei Stufen: in der ersten wird gebaut, in der zweiten laeuft nur noch das Ergebnis.
# So landet der ganze Maven-Werkzeugkasten NICHT im fertigen Abbild - das macht es klein
# und enthaelt keinen Quelltext.
#
# Der Bau-Kontext ist das Wurzelverzeichnis des Projekts, nicht dieser Ordner. Nur so
# kommt der Eltern-POM mit ins Abbild. Festgelegt ist das in docker-compose.yml.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Zuerst nur die POM-Dateien. Aendert sich am Quelltext etwas, bleibt die Schicht mit den
# heruntergeladenen Abhaengigkeiten gueltig, und der Bau geht schneller.
COPY pom.xml ./
COPY chat-service/pom.xml chat-service/
COPY batch-writer/pom.xml batch-writer/

COPY chat-service/src chat-service/src

# -pl waehlt dieses Modul, -am baut den Eltern-POM mit.
# Die Tests laufen hier NICHT: sie brauchen Docker, und wir sind schon in Docker.
# Geprueft wird mit "mvn clean test" auf dem Rechner, nicht beim Bauen des Abbilds.
RUN mvn -B -q -pl chat-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine

# curl, damit man im Betrieb von innen gegen die eigene Schnittstelle pruefen kann.
# Nach aussen ist kein Port offen, also ist "von innen" der einzige Weg.
RUN apk add --no-cache curl

WORKDIR /app
COPY --from=build /build/chat-service/target/*.jar app.jar

# expose ist nur eine Notiz im Abbild: der Port ist damit im Docker-Netz erreichbar,
# aber NICHT auf dem Rechner veroeffentlicht. Veroeffentlichen wuerde "ports" tun.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
