# Multi-stage build for Jervis server application
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy gradle files
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle/

# Copy source code
COPY common common/
COPY server server/
COPY api-client api-client/

# Build the application
RUN gradle :server:build -x test --no-daemon

# Runtime stage
FROM eclipse-temurin:21-jre

# Install required tools
RUN apt-get update && \
    apt-get install -y \
    git \
    curl \
    wget \
    unzip \
    python3 \
    python3-pip \
    && rm -rf /var/lib/apt/lists/*

# Install Joern
ENV JOERN_VERSION=2.0.0
ENV JOERN_HOME=/opt/joern

RUN mkdir -p ${JOERN_HOME} && \
    cd /tmp && \
    wget https://github.com/joernio/joern/releases/download/v${JOERN_VERSION}/joern-cli.zip && \
    unzip joern-cli.zip -d ${JOERN_HOME} && \
    rm joern-cli.zip && \
    chmod +x ${JOERN_HOME}/joern-cli/joern

# Add Joern to PATH
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"

# Create application directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/server/build/libs/*.jar app.jar

# Create data directory for client/project storage
RUN mkdir -p /data

# Set environment variables
ENV JAVA_OPTS="-Xmx2g -Xms512m"
ENV DATA_ROOT_DIR=/data
ENV SERVER_PORT=8080

# Expose application port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8080/actuator/health || exit 1

# Volume for data persistence
VOLUME ["/data"]

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /app/app.jar"]
