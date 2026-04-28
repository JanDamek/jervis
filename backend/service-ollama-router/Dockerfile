FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/service-ollama-router

COPY backend/service-ollama-router/build/libs/service-ollama-router-*.jar service-ollama-router.jar

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1080 (ollama-router).
RUN groupadd -g 1000 jervis && \
    useradd -u 1080 -g jervis -m -s /bin/bash ollama-router && \
    chown -R ollama-router:jervis /opt/jervis
USER ollama-router

EXPOSE 5501 8080

ENV ROUTER_GRPC_PORT=5501
ENV HEALTH_PORT=8080
ENV HEALTH_HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["sh", "-c", "umask 002 && exec java -jar service-ollama-router.jar"]
