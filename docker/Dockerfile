FROM eclipse-temurin:21-jre-alpine AS builder
WORKDIR application

# Keep the existing amd64 dockerize support explicit.
ARG DOCKERIZE_VERSION
RUN test "$(uname -m)" = x86_64
RUN wget -O dockerize.tar.gz https://github.com/jwilder/dockerize/releases/download/${DOCKERIZE_VERSION}/dockerize-alpine-linux-amd64-${DOCKERIZE_VERSION}.tar.gz
RUN tar xzf dockerize.tar.gz
RUN chmod +x dockerize

ARG ARTIFACT_NAME
COPY ${ARTIFACT_NAME}.jar application.jar
# All modules use the same Boot 3 packaging plugin, including legacy runtime modules.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre-alpine

WORKDIR application

# Dockerize
COPY --from=builder application/dockerize ./

ARG EXPOSED_PORT
EXPOSE ${EXPOSED_PORT}

ENV SPRING_PROFILES_ACTIVE=docker

COPY --from=builder application/extracted/dependencies/ ./
COPY --from=builder application/extracted/spring-boot-loader/ ./
COPY --from=builder application/extracted/snapshot-dependencies/ ./
COPY --from=builder application/extracted/application/ ./
ENTRYPOINT ["java", "-jar", "application.jar"]
