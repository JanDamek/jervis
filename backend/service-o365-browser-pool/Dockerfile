FROM mcr.microsoft.com/playwright/python:v1.49.0-noble

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY app/ app/

# Create browser profiles directory
RUN mkdir -p /browser-profiles

# Playwright image already has UID 1000 (pwuser), reuse it
RUN chown -R 1000:1000 /app /browser-profiles

USER 1000

EXPOSE 8090

CMD ["python", "-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8090"]
