FROM eclipse-temurin:17-jdk-jammy
WORKDIR /app

RUN apt-get update \
 && apt-get install -y ca-certificates openssl libnss3 \
 && update-ca-certificates

COPY app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java","-Djavax.net.ssl.trustStore=/opt/java/openjdk/lib/security/cacerts","-Djavax.net.ssl.trustStorePassword=changeit","-Djavax.net.ssl.trustStoreType=JKS","-Djava.security.egd=file:/dev/./urandom","-jar","app.jar"]
