# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl git && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/server
COPY backend/server/build/libs/server-*.jar server.jar

# Per uid-map.md: shared gid 1000 jervis + per-service uid (1010 here).
# PVC audit `ls -la` then shows which service wrote each file while
# group sharing keeps R/W across pods on the same PVC.
RUN groupadd -g 1000 jervis && \
    useradd -u 1010 -g jervis -m -s /bin/bash server && \
    chown -R server:jervis /opt/jervis
USER server

ENV JAVA_OPTS="-Xmx4g -Xms1g"
EXPOSE 5500
VOLUME ["/opt/jervis/data"]
ENTRYPOINT ["sh", "-c", "umask 002 && java ${JAVA_OPTS} -jar /opt/jervis/server/server.jar --spring.config.additional-location=optional:file:/opt/jervis/server/config/"]
