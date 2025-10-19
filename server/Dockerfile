# Multi-stage build for Jervis server application
FROM gradle:8.5-jdk21 AS builder

WORKDIR /app

# Copy Gradle and project files from server module context
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle gradle/

# Copy server sources (entire module directory)
COPY . .

# Build the application (server module as standalone)
RUN gradle build -x test --no-daemon

# Resolve Joern version from Gradle (server module) and install it in the builder image
RUN JOERN_VERSION=$(gradle -q printJoernVersion --no-daemon) \
    && echo $JOERN_VERSION > /joern-version.txt \
    && mkdir -p /opt/joern \
    && cd /tmp \
    && wget https://github.com/joernio/joern/releases/download/v${JOERN_VERSION}/joern-cli.zip \
    && unzip joern-cli.zip -d /opt/joern \
    && rm joern-cli.zip \
    && chmod +x /opt/joern/joern-cli/joern

# Runtime stage
FROM eclipse-temurin:21-jre

# Install required tools and OCR dependencies
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    git \
    openssh-client \
    gnupg \
    gnupg-agent \
    pinentry-curses \
    curl \
    wget \
    unzip \
    python3 \
    python3-pip \
    tesseract-ocr \
    tesseract-ocr-eng \
    tesseract-ocr-ces \
    tesseract-ocr-spa \
    tesseract-ocr-slk \
    tesseract-ocr-fin \
    tesseract-ocr-nor \
    tesseract-ocr-dan \
    tesseract-ocr-pol \
    tesseract-ocr-deu \
    tesseract-ocr-hun \
    imagemagick \
    poppler-utils \
    ghostscript \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Configure environment for Tesseract
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata

# Default OCR languages (can be overridden at runtime)
ENV TIKA_OCR_ENABLED=true
ENV TIKA_OCR_LANG=eng+ces+spa+slk+fin+nor+dan+pol+deu+hun
ENV TIKA_OCR_TIMEOUT_MS=120000

# Joern
ENV JOERN_HOME=/opt/joern
COPY --from=builder /opt/joern ${JOERN_HOME}
COPY --from=builder /joern-version.txt ${JOERN_HOME}/VERSION
ENV PATH="${JOERN_HOME}/joern-cli:${PATH}"

# Create application directory
WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /app/build/libs/*.jar app.jar

# Create workspace directory structure for all server-managed files
RUN mkdir -p /opt/jervis/data/keys/ssh \
    && mkdir -p /opt/jervis/data/keys/gpg \
    && chmod 700 /opt/jervis/data/keys/ssh \
    && chmod 700 /opt/jervis/data/keys/gpg

# Set environment variables
ENV JAVA_OPTS="-Xmx2g -Xms512m"
ENV DATA_ROOT_DIR=/opt/jervis/data
ENV SERVER_PORT=5500

# Expose application port
EXPOSE 5500

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:${SERVER_PORT}/actuator/health || exit 1

# Volume for workspace persistence (clients, tmp, storage, cache)
VOLUME ["/opt/jervis/data"]

# Run the application
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Ddata.root.dir=${DATA_ROOT_DIR} -jar /app/app.jar"]
