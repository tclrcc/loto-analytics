# =====================
# ÉTAPE 1 : BUILD
# =====================
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# On copie d'abord le pom.xml pour profiter du cache Docker sur les dépendances
COPY pom.xml .
# (Astuce : Si vous aviez une commande "mvn dependency:go-offline", ce serait ici)

COPY src ./src
RUN mvn clean package -DskipTests

# =====================
# ÉTAPE 2 : RUN
# =====================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Installation des certificats racines pour éviter les erreurs SSL avec la FDJ/Gmail
RUN apt-get update && apt-get install -y \
    ca-certificates-java \
    openssl \
    libnss3 \
    && update-ca-certificates -f \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Lancement avec paramétrage SSL forcé et source d'entropie rapide (/dev/urandom)
ENTRYPOINT ["java", \
            "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts", \
            "-Djavax.net.ssl.trustStorePassword=changeit", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "app.jar"]
