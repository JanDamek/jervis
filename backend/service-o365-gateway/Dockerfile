FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/service-o365-gateway

COPY backend/service-o365-gateway/build/libs/service-o365-gateway-*.jar service-o365-gateway.jar

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1090.
RUN groupadd -g 1000 jervis && \
    useradd -u 1090 -g jervis -m -s /bin/bash o365-gateway && \
    chown -R o365-gateway:jervis /opt/jervis
USER o365-gateway

EXPOSE 8080

ENV PORT=8080
ENV HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "service-o365-gateway.jar"]
