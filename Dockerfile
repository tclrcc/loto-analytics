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
# RUN
# =====================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Installation des certificats racines (Sécurité)
RUN apt-get update && apt-get install -y \
    ca-certificates-java \
    && update-ca-certificates -f \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# On utilise 'sh -c' pour que la variable $JAVA_OPTS soit bien lue.
# On utilise 'exec' pour que Java remplace le shell et reçoive les signaux d'arrêt correctement.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
