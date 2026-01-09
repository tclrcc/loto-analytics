FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

RUN apt-get update \
 && apt-get install -y ca-certificates openssl libnss3 \
 && update-ca-certificates

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-Djavax.net.ssl.trustStore=/opt/java/openjdk/lib/security/cacerts","-Djavax.net.ssl.trustStorePassword=changeit","-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]
