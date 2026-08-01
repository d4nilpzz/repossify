FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# curl is only used by the healthcheck below.
RUN apk add --no-cache curl

COPY --from=builder /build/target/repossify-*.jar repossify.jar

VOLUME /data
EXPOSE 8080

# 0.0.0.0 rather than the default, or the port published by Docker reaches nothing.
ENV REPOSSIFY_OPTS=""

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/api/version || exit 1

CMD ["sh", "-c", "exec java -jar repossify.jar --working-directory /data --hostname 0.0.0.0 $REPOSSIFY_OPTS"]
