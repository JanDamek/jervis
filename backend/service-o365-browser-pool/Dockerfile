FROM mcr.microsoft.com/playwright/python:v1.49.0-noble

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

# Create browser profiles directory
RUN mkdir -p /browser-profiles

RUN useradd -m -u 1000 -s /bin/bash jervis && \
    chown -R jervis:jervis /app /browser-profiles

USER 1000

EXPOSE 8090

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
