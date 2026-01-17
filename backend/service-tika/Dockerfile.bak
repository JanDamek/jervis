# --- Build Stage ---
FROM gradle:9.2.1-jdk21-jammy AS builder
WORKDIR /app
COPY . .
RUN DOCKER_BUILD=true gradle :backend:service-tika:jar --no-daemon

# --- Run Stage ---
# Dockerfile pro jervis-tika (optimized - lokální JAR)
FROM eclipse-temurin:21-jdk-jammy

# Install required tools and TiKa dependencies
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

# Default OCR environment (can be overridden at runtime)
ENV TIKA_OCR_ENABLED=true
ENV TIKA_OCR_LANG=eng+ces+spa+slk+fin+nor+dan+pol+deu+hun
ENV TIKA_OCR_TIMEOUT_MS=120000

WORKDIR /opt/jervis/tika
COPY --from=builder /app/backend/service-tika/build/libs/*.jar jervis-tika.jar
ENV JAVA_OPTS="-Xmx2g -Xms512m"
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
    CMD curl -f http://localhost:8081/actuator/health || exit 1
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/tika/jervis-tika.jar --spring.config.additional-location=optional:file:/opt/jervis/tika/config/"]
