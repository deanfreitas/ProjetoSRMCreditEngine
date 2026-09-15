# Build em duas etapas: a imagem final nao carrega Maven nem codigo-fonte.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Camada de dependencias separada: alterar codigo nao invalida o download do Maven.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Os testes de integracao exigem Docker (Testcontainers) e rodam no CI, nao aqui.
RUN mvn -B -q clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app

# Usuario sem privilegios: container de aplicacao financeira nao roda como root.
RUN addgroup -S srm && adduser -S srm -G srm
COPY --from=build /build/target/*.jar app.jar
RUN chown -R srm:srm /app
USER srm

EXPOSE 8080

# Container morre e o orquestrador reinicia: falha rapida e preferivel a processo zumbi.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
