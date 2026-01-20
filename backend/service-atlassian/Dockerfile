# --- Run Stage ---
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*
WORKDIR /opt/jervis/atlassian
COPY backend/service-atlassian/build/libs/*.jar jervis-atlassian.jar
ENV JAVA_OPTS="-Xmx1g -Xms256m"
EXPOSE 8084
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/atlassian/jervis-atlassian.jar"]
