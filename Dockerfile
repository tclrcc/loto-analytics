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

# Installer les certificats système pour Java
RUN apt-get update \
 && apt-get install -y ca-certificates-java openssl libnss3 \
 && update-ca-certificates -f \
 && rm -rf /var/lib/apt/lists/*

# Copier le jar buildé
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Forcer Java à utiliser le truststore par défaut
ENTRYPOINT ["java", "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts", "-Djavax.net.ssl.trustStorePassword=changeit", "-Djava.security.egd=file:/dev/./urandom", "-jar", "app.jar"]
