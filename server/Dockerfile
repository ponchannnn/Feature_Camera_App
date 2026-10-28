FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY zoomcapture_server ./zoomcapture_server
RUN pip install --no-cache-dir .
ENV ZC_DATA_DIR=/data
EXPOSE 8443
CMD ["python", "-m", "zoomcapture_server", "serve"]
