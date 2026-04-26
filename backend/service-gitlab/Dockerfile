FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/service-gitlab

COPY backend/service-gitlab/build/libs/service-gitlab-*.jar service-gitlab.jar

# Per uid-map.md: shared gid 1000 jervis + per-service uid 1102.
RUN groupadd -g 1000 jervis && \
    useradd -u 1102 -g jervis -m -s /bin/bash jervis-gitlab && \
    chown -R jervis-gitlab:jervis /opt/jervis
USER jervis-gitlab

EXPOSE 8086

ENV PORT=8086
ENV HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8086/ || exit 1

ENTRYPOINT ["java", "-jar", "service-gitlab.jar"]
