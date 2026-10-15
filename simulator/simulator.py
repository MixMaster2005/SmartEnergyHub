#!/usr/bin/env python3
"""
SmartEnergy Hub - Simulateur de capteurs (EF-01, EF-02, EF-03)

Genere periodiquement des mesures energetiques plausibles pour plusieurs
capteurs et les envoie au Collector Service via HTTP POST /api/measurements.

Configuration par variables d'environnement (ENF-02) :
  SIMULATOR_INTERVAL_SECONDS  intervalle entre deux cycles (defaut 2)
  SIMULATOR_SENSORS           liste de sensorId separes par des virgules
  SIMULATOR_COLLECTOR_URL     URL du Collector (defaut http://localhost:8081/api/measurements)
  SIMULATOR_PEAK_FACTOR       facteur multiplicatif du scenario de pic (defaut 3)
  SIMULATOR_PEAK_PROBABILITY  probabilite de declenchement d'un pic (defaut 0.05)
"""
from __future__ import annotations

import math
import os
import random
import signal
import sys
import time
from datetime import datetime, timezone

import requests

RUNNING = True


def _env_float(name: str, default: float) -> float:
    try:
        return float(os.environ.get(name, default))
    except (TypeError, ValueError):
        return default


INTERVAL = _env_float("SIMULATOR_INTERVAL_SECONDS", 2.0)
PEAK_FACTOR = _env_float("SIMULATOR_PEAK_FACTOR", 3.0)
PEAK_PROBABILITY = _env_float("SIMULATOR_PEAK_PROBABILITY", 0.05)
COLLECTOR_URL = os.environ.get(
    "SIMULATOR_COLLECTOR_URL", "http://localhost:8081/api/measurements"
)
SENSORS = [
    s.strip()
    for s in os.environ.get(
        "SIMULATOR_SENSORS", "SENSOR-001,SENSOR-002,SENSOR-003,SENSOR-004,SENSOR-005"
    ).split(",")
    if s.strip()
]


def _handle_signal(signum, _frame) -> None:
    global RUNNING
    RUNNING = False
    print(f"[simulator] signal {signum} recu, arret en cours...", flush=True)


def build_measurement(sensor_id: str, base_power: float, tick: int) -> dict:
    """Construit une mesure plausible, avec pic ponctuel (EF-03)."""
    # Variation sinusoidale lente + bruit controle.
    periodic = 0.5 * math.sin(tick / 10.0)
    power = base_power + periodic + random.uniform(-0.2, 0.2)
    power = max(0.0, round(power, 3))

    peak = random.random() < PEAK_PROBABILITY
    if peak:
        power = round(power * PEAK_FACTOR, 3)
        print(f"[simulator] PIC injecte sur {sensor_id}: {power} kW", flush=True)

    return {
        "sensorId": sensor_id,
        "timestamp": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "powerKw": power,
        "voltage": round(random.uniform(220.0, 235.0), 2),
        "temperature": round(random.uniform(20.0, 34.0), 2),
    }


def send(session: requests.Session, measurement: dict) -> None:
    try:
        resp = session.post(COLLECTOR_URL, json=measurement, timeout=5)
        level = "OK" if resp.status_code in (200, 201, 202) else "KO"
        print(
            f"[simulator] {level} {resp.status_code} {measurement['sensorId']} "
            f"{measurement['powerKw']} kW",
            flush=True,
        )
    except requests.RequestException as exc:
        print(f"[simulator] ERREUR envoi: {exc}", flush=True)


def main() -> int:
    signal.signal(signal.SIGINT, _handle_signal)
    signal.signal(signal.SIGTERM, _handle_signal)

    base_power = {sensor: random.uniform(2.0, 5.0) for sensor in SENSORS}
    print(
        f"[simulator] {len(SENSORS)} capteurs -> {COLLECTOR_URL} "
        f"(intervalle {INTERVAL}s)",
        flush=True,
    )

    session = requests.Session()
    tick = 0
    while RUNNING:
        for sensor in SENSORS:
            send(session, build_measurement(sensor, base_power[sensor], tick))
        tick += 1
        time.sleep(INTERVAL)

    print("[simulator] arrete.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
