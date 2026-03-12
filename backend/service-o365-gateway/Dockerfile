FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/service-o365-gateway

COPY backend/service-o365-gateway/build/libs/service-o365-gateway-*.jar service-o365-gateway.jar

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /opt/jervis
USER 1000

EXPOSE 8080

ENV PORT=8080
ENV HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "service-o365-gateway.jar"]
