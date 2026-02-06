# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl git && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/server
COPY backend/server/build/libs/server-*.jar jervis-server.jar

ENV JAVA_OPTS="-Xmx4g -Xms1g"
EXPOSE 5500
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/server/jervis-server.jar --spring.config.additional-location=optional:file:/opt/jervis/server/config/"]
