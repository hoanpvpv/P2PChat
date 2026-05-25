FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -B -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/bootstrap-server.jar /app/bootstrap-server.jar
EXPOSE 9000 9001 8080 8081
ENTRYPOINT ["java", "-jar", "/app/bootstrap-server.jar"]
