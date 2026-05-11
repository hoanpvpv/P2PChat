FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn clean package -B -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/target/peer-node.jar /app/peer-node.jar
RUN mkdir /app/data
EXPOSE 5001 5002 5003 5004 3000
ENTRYPOINT ["java", "-jar", "/app/peer-node.jar"]
