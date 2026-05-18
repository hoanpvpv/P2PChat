FROM node:20-alpine AS build-web
WORKDIR /app
COPY peer-web/package*.json ./
RUN npm install
COPY peer-web/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-17 AS build-java
WORKDIR /app
COPY peer-node/pom.xml .
RUN mvn dependency:go-offline -B
COPY peer-node/src ./src
COPY --from=build-web /app/build ./src/main/resources/static
RUN mvn clean package -B -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build-java /app/target/peer-node.jar /app/peer-node.jar
RUN mkdir /app/data
EXPOSE 5001 5002 5003 5004 6001 6002 6003 6004 3000
ENTRYPOINT ["java", "-jar", "/app/peer-node.jar"]
