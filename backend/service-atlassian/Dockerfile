# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/jervis/atlassian
COPY backend/service-atlassian/build/libs/service-atlassian-*.jar atlassian.jar

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1100.
RUN groupadd -g 1000 jervis && \
    useradd -u 1100 -g jervis -m -s /bin/bash atlassian && \
    chown -R atlassian:jervis /opt/jervis
USER atlassian

ENV JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 8084
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/atlassian/atlassian.jar"]
