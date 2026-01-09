FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# --- AJOUT CRITIQUE ICI ---
# On met à jour les dépôts et on force l'installation des certificats CA et des libs OpenSSL
RUN apt-get update && \
    apt-get install -y ca-certificates openssl && \
    update-ca-certificates
# --------------------------

COPY --from=build /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts","-Djavax.net.ssl.trustStorePassword=changeit","-jar","app.jar"]

