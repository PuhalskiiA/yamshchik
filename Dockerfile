# Сборка. Тесты здесь не запускаются: им нужна база данных
FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY src src
RUN --mount=type=cache,target=/root/.m2 sh ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:25-jre
RUN useradd --system --no-create-home yamshchik
WORKDIR /app
COPY --from=build /workspace/target/yamshchik-*.jar app.jar
USER yamshchik
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
