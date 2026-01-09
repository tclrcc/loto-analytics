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

# Certificats système (utile pour curl / openssl, pas obligatoire pour Java mais OK)
RUN apt-get update \
 && apt-get install -y ca-certificates openssl libnss3 \
 && update-ca-certificates \
 && rm -rf /var/lib/apt/lists/*

# Jar Spring Boot
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# 🚨 NE PAS FORCER LE TRUSTSTORE (Java 17 = PKCS12 automatique)
ENTRYPOINT ["java","-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]
