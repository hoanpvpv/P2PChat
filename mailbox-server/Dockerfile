FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -B -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/mailbox-server.jar /app/mailbox-server.jar
RUN mkdir -p /app/data
EXPOSE 9100
ENTRYPOINT ["java", "-jar", "/app/mailbox-server.jar"]
