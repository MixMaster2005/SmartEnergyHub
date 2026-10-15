# ============================================================
# Simulateur de capteurs - image legere
# ============================================================
FROM python:3.12-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY simulator.py .
RUN useradd -r -u 1001 app
USER app
ENTRYPOINT ["python", "-u", "simulator.py"]
