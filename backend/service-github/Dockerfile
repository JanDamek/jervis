FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl && \
    rm -rf /var/lib/apt/lists/*

WORKDIR /opt/jervis/service-github

COPY backend/service-github/build/libs/service-github-*.jar service-github.jar

EXPOSE 8085

ENV PORT=8085
ENV HOST=0.0.0.0

HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD curl -f http://localhost:8085/ || exit 1

ENTRYPOINT ["java", "-jar", "service-github.jar"]
