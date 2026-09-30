FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY src src
RUN mkdir out && javac -d out src/*.java
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/out out
# Default command is overridden per service in docker-compose.yml
CMD ["java", "-cp", "out", "Coordinator"]
