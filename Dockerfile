# =====================
# BUILD
# =====================
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# =====================
# RUN
# =====================
FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

# Installer les certificats système
RUN apt-get update \
 && apt-get install -y ca-certificates openssl libnss3 \
 && update-ca-certificates \
 && mkdir -p /etc/ssl/certs/java \
 && keytool -importkeystore \
      -srckeystore $(dirname $(readlink -f $(which java)))/../lib/security/cacerts \
      -destkeystore /etc/ssl/certs/java/cacerts \
      -storepass changeit \
      -noprompt \
 || true \
 && rm -rf /var/lib/apt/lists/*

# Copier le jar
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Forcer Java à utiliser ce truststore
ENTRYPOINT ["java", "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts", "-Djavax.net.ssl.trustStorePassword=changeit", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
