FROM eclipse-temurin:21-jdk-alpine AS build

WORKDIR /workspace/backend
COPY backend/mvnw backend/pom.xml ./
COPY backend/.mvn .mvn
RUN chmod +x mvnw
COPY backend/src src
COPY prompts /workspace/prompts
RUN ./mvnw --batch-mode --no-transfer-progress package

FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY --from=build /workspace/target/patchpilot-backend-0.1.0-SNAPSHOT.jar /app/app.jar
EXPOSE 8080
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
