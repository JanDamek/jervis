# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/jervis/atlassian
COPY backend/service-atlassian/build/libs/service-atlassian-*.jar jervis-atlassian.jar

RUN useradd -m -u 1000 -s /bin/bash jervis && chown -R jervis:jervis /opt/jervis
USER 1000

ENV JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 8084
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/atlassian/jervis-atlassian.jar"]
