# --- Run Stage ---
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
COPY backend/service-tika/build/libs/*.jar jervis-tika.jar
ENV JAVA_OPTS="-Xmx2g -Xms512m"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar /opt/jervis/tika/jervis-tika.jar"]
