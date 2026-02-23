FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "target/url-shortener-0.0.1-SNAPSHOT.jar"]
