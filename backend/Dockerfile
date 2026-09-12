# Stage 1: Build with Maven
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw
RUN ./mvnw dependency:go-offline -B

COPY src src
RUN ./mvnw clean package -DskipTests -B
# Produces /app/target/backend-0.0.1-SNAPSHOT.jar

# Stage 2: Lightweight runtime
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=builder /app/target/backend-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8081

ENTRYPOINT ["java", "-Xms256m", "-Xmx1g", "-XX:+UseSerialGC", "-Dfile.encoding=UTF-8", "-jar", "app.jar"]
