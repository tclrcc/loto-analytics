# =====================
# ÉTAPE 1 : BUILD
# =====================
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Optimisation du cache des dépendances
COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
# On compile sans lancer les tests pour accélérer le build CI/CD
RUN mvn clean package -DskipTests

# =====================
# RUN
# =====================
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 1. Installation Python & Pip
RUN apt-get update && apt-get install -y \
    ca-certificates-java \
    python3 \
    python3-pip \
    && update-ca-certificates -f \
    && rm -rf /var/lib/apt/lists/*

# 2. Copie des scripts et installation des dépendances
COPY scripts ./scripts

# CORRECTION ICI : On retire "--break-system-packages" car pip est en version 22.0.2 sur Jammy
RUN pip3 install -r scripts/requirements.txt

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
