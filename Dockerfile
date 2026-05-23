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
RUN mkdir -p /app/data
# Peer ports are passed at runtime by ./run.sh (web_port, peer_port=web+1000, file_port=peer+1000).
# Container publishes whichever ports run.sh maps; EXPOSE here is informational only.
ENTRYPOINT ["java", "-jar", "/app/peer-node.jar"]
