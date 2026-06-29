FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

COPY target/repossify-*.jar repossify.jar

RUN mkdir -p /data/repos /data/logs /data/content

VOLUME /data

ENV REPOSSIFY_WORKING_DIR=/data
ENV REPOSSIFY_HOST=0.0.0.0
ENV REPOSSIFY_PORT=8080

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "repossify.jar"]
CMD ["--hostname", "0.0.0.0", "--port", "8080", "--working-directory", "/data"]
