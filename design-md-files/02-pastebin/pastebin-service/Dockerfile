FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .
RUN chmod +x mvnw && ./mvnw dependency:go-offline -q
COPY src ./src
RUN ./mvnw package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
RUN addgroup -S pastebin && adduser -S pastebin -G pastebin
WORKDIR /app
COPY --from=builder /app/target/pastebin-service.jar app.jar
USER pastebin
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
