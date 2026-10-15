#!/usr/bin/env python3
"""
SmartEnergy Hub - Apercu independant / simulation continue des mesures (EF-01..EF-03)

Fichier AUTONOME : aucune dependance externe, aucun envoi HTTP, aucune lecture du
Collector. Il genere les mesures des capteurs simules et les imprime en JSON sur la
sortie standard, pour la partie "simulation des capteurs" (CDC etape 2, section 10).

Formule de generation (CDC 10.1) :
    Pt = Pbase + A * sin(2*pi*t/T) + epsilon_t
avec :
    Pbase        consommation moyenne par capteur (aleatoire 2.0..5.0 kW)
    A            amplitude des variations (--amplitude, defaut 0.5)
    T            periode en secondes (--period-seconds, defaut 120)
    epsilon_t    bruit aleatoire uniforme (--noise, defaut 0.2)
    t            temps ecoule en secondes (tick * intervalle)
Anomalie volontaire : puissance multipliee par un facteur (--peak-factor, defaut 3),
soit periodiquement (--peak-every N cycles) soit sur une fenetre temporelle
(--peak-at-second + --peak-duration).

Modes :
    1) Apercu ponctuel (defaut) : genere --ticks cycles d'un coup.
    2) Stream (--duration-seconds > 0) : imprime en continu pendant la duree demandee
       (test CDC 10.2 : 5 capteurs pendant >= 2 minutes).

Usage :
    python3 simulator/preview_measurements.py
    python3 simulator/preview_measurements.py --sensors 5 --ticks 3 --seed 42
    python3 simulator/preview_measurements.py --ticks 5 --peak-every 3
    # Test CDC 10.2 (2 minutes, impression continue, un pic au milieu) :
    python3 simulator/preview_measurements.py --sensors 5 --duration-seconds 120 \
        --interval-seconds 2 --seed 42 --peak-at-second 60 --peak-duration 10
"""
from __future__ import annotations

import argparse
import json
import math
import random
import sys
import time
from datetime import datetime, timedelta, timezone

DEFAULT_SENSORS = ["SENSOR-001", "SENSOR-002", "SENSOR-003", "SENSOR-004", "SENSOR-005"]
DEFAULT_PEAK_FACTOR = 3.0
DEFAULT_AMPLITUDE = 0.5
DEFAULT_PERIOD_SECONDS = 120.0
DEFAULT_NOISE = 0.2


def build_measurement(
    sensor_id: str,
    base_power: float,
    elapsed_seconds: float,
    *,
    amplitude: float,
    period_seconds: float,
    noise: float,
    peak: bool,
    peak_factor: float,
    measured_at: datetime,
) -> dict:
    """Construit une mesure selon Pt = Pbase + A.sin(2.pi.t/T) + epsilon_t."""
    periodic = amplitude * math.sin(2.0 * math.pi * elapsed_seconds / period_seconds)
    power = base_power + periodic + random.uniform(-noise, noise)
    power = max(0.0, round(power, 3))

    if peak:
        power = round(power * peak_factor, 3)

    return {
        "sensorId": sensor_id,
        "timestamp": measured_at.isoformat().replace("+00:00", "Z"),
        "powerKw": power,
        "voltage": round(random.uniform(220.0, 235.0), 2),
        "temperature": round(random.uniform(20.0, 34.0), 2),
    }


def validate(measurement: dict) -> list[str]:
    """Verifie les regles de validation du contrat de donnees."""
    errors: list[str] = []
    if not measurement.get("sensorId"):
        errors.append("sensorId vide")
    ts = measurement.get("timestamp", "")
    if not (isinstance(ts, str) and ts.endswith("Z")):
        errors.append("timestamp non UTC (suffixe Z attendu)")
    else:
        try:
            datetime.fromisoformat(ts.replace("Z", "+00:00"))
        except ValueError:
            errors.append("timestamp ISO-8601 invalide")
    for field, rule in (
        ("powerKw", lambda v: isinstance(v, (int, float)) and v >= 0),
        ("voltage", lambda v: isinstance(v, (int, float)) and v > 0),
        ("temperature", lambda v: isinstance(v, (int, float))),
    ):
        if not rule(measurement.get(field)):
            errors.append(f"{field} invalide ({measurement.get(field)!r})")
    return errors


def parse_args(argv: list[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Simulation autonome des capteurs.")
    parser.add_argument("--sensors", type=int, default=len(DEFAULT_SENSORS),
                        help="nombre de capteurs generes (defaut 5)")
    parser.add_argument("--ticks", type=int, default=3,
                        help="nombre de cycles en mode apercu ponctuel (defaut 3)")
    parser.add_argument("--duration-seconds", type=float, default=0.0,
                        help="mode stream : duree totale en secondes (0 = apercu ponctuel)")
    parser.add_argument("--interval-seconds", type=float, default=2.0,
                        help="ecart entre deux cycles, en secondes (defaut 2)")
    parser.add_argument("--amplitude", type=float, default=DEFAULT_AMPLITUDE,
                        help="amplitude A des variations (defaut 0.5)")
    parser.add_argument("--period-seconds", type=float, default=DEFAULT_PERIOD_SECONDS,
                        help="periode T en secondes de la sinusoide (defaut 120)")
    parser.add_argument("--noise", type=float, default=DEFAULT_NOISE,
                        help="amplitude du bruit epsilon_t (defaut 0.2)")
    parser.add_argument("--peak-factor", type=float, default=DEFAULT_PEAK_FACTOR,
                        help="facteur multiplicatif du pic (defaut 3)")
    parser.add_argument("--peak-every", type=int, default=0,
                        help="force un pic tous les N cycles sur le 1er capteur (0 = aucun)")
    parser.add_argument("--peak-at-second", type=float, default=None,
                        help="debut (s) de la fenetre d'anomalie en mode stream")
    parser.add_argument("--peak-duration", type=float, default=10.0,
                        help="duree (s) de la fenetre d'anomalie (defaut 10)")
    parser.add_argument("--seed", type=int, default=None,
                        help="graine aleatoire pour un resultat reproductible")
    parser.add_argument("--json", action="store_true",
                        help="n'affiche que le tableau JSON brut (mode apercu ponctuel)")
    return parser.parse_args(argv)


def _is_peak(args, tick: int, elapsed: float, sensor: str, first_sensor: str) -> bool:
    if sensor != first_sensor:
        return False
    if args.peak_every > 0 and (tick + 1) % args.peak_every == 0:
        return True
    if args.peak_at_second is not None:
        return args.peak_at_second <= elapsed < args.peak_at_second + args.peak_duration
    return False


def run_preview(args, sensors, base_power, first_sensor, start) -> int:
    """Genere --ticks cycles d'un coup, imprime et valide."""
    measurements: list[dict] = []
    peak_flags: list[bool] = []
    for tick in range(args.ticks):
        elapsed = tick * args.interval_seconds
        measured_at = start + timedelta(seconds=elapsed)
        for sensor in sensors:
            peak = _is_peak(args, tick, elapsed, sensor, first_sensor)
            measurements.append(build_measurement(
                sensor, base_power[sensor], elapsed,
                amplitude=args.amplitude, period_seconds=args.period_seconds,
                noise=args.noise, peak=peak, peak_factor=args.peak_factor,
                measured_at=measured_at,
            ))
            peak_flags.append(peak)

    all_errors: list[str] = []
    for idx, measurement in enumerate(measurements):
        errors = validate(measurement)
        if errors:
            all_errors.append(f"mesure #{idx} ({measurement['sensorId']}): {', '.join(errors)}")

    if args.json:
        print(json.dumps(measurements, indent=2, ensure_ascii=False))
    else:
        print("=" * 72)
        print(f"SmartEnergy Hub - apercu de {len(measurements)} mesures simulees "
              f"({len(sensors)} capteurs x {args.ticks} cycles)")
        print(f" formule : Pt = Pbase + {args.amplitude}.sin(2.pi.t/{args.period_seconds}) + bruit({args.noise})")
        print("=" * 72)
        for idx, measurement in enumerate(measurements):
            tag = f"  <-- PIC x{args.peak_factor}" if peak_flags[idx] else ""
            print(json.dumps(measurement, ensure_ascii=False) + tag)
        print("-" * 72)
        if all_errors:
            print(f"CONTRAT INVALIDE : {len(all_errors)} erreur(s)")
            for error in all_errors:
                print(f"  - {error}")
        else:
            print(f"CONTRAT VALIDE : {len(measurements)} mesures conformes au contrat.")

    return 1 if all_errors else 0


def run_stream(args, sensors, base_power, first_sensor, start) -> int:
    """Imprime en continu pendant --duration-seconds (test CDC 10.2)."""
    total = 0
    errors = 0
    peak_count = 0
    tick = 0
    print("=" * 72)
    print(f"SmartEnergy Hub - simulation continue : {len(sensors)} capteurs, "
          f"{args.duration_seconds:.0f}s, une mesure / {args.interval_seconds}s")
    if args.peak_at_second is not None:
        print(f" anomalie : PIC x{args.peak_factor} sur {first_sensor} de t={args.peak_at_second}s "
              f"a t={args.peak_at_second + args.peak_duration}s")
    elif args.peak_every > 0:
        print(f" anomalie : PIC x{args.peak_factor} sur {first_sensor} tous les {args.peak_every} cycles")
    print("=" * 72)

    deadline = time.monotonic() + args.duration_seconds
    while True:
        elapsed = tick * args.interval_seconds
        if elapsed >= args.duration_seconds:
            break
        measured_at = datetime.now(timezone.utc).replace(microsecond=0)
        for sensor in sensors:
            peak = _is_peak(args, tick, elapsed, sensor, first_sensor)
            measurement = build_measurement(
                sensor, base_power[sensor], elapsed,
                amplitude=args.amplitude, period_seconds=args.period_seconds,
                noise=args.noise, peak=peak, peak_factor=args.peak_factor,
                measured_at=measured_at,
            )
            if validate(measurement):
                errors += 1
            total += 1
            if peak:
                peak_count += 1
            tag = f"  <-- PIC x{args.peak_factor}" if peak else ""
            print(json.dumps(measurement, ensure_ascii=False) + tag, flush=True)
        tick += 1
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            break
        time.sleep(min(args.interval_seconds, remaining))

    print("-" * 72)
    print(f"TOTAL: {total} mesures ({len(sensors)} capteurs x {tick} cycles), "
          f"pics: {peak_count}")
    if errors:
        print(f"CONTRAT INVALIDE : {errors} mesure(s) non conforme(s).")
        return 1
    print(f"CONTRAT VALIDE : {total} mesures conformes au contrat.")
    return 0


def main(argv: list[str] | None = None) -> int:
    args = parse_args(sys.argv[1:] if argv is None else argv)
    if args.seed is not None:
        random.seed(args.seed)

    sensors = [f"SENSOR-{i + 1:03d}" for i in range(args.sensors)]
    first_sensor = sensors[0]
    base_power = {sensor: random.uniform(2.0, 5.0) for sensor in sensors}
    start = datetime.now(timezone.utc).replace(microsecond=0)

    if args.duration_seconds > 0:
        return run_stream(args, sensors, base_power, first_sensor, start)
    return run_preview(args, sensors, base_power, first_sensor, start)


if __name__ == "__main__":
    sys.exit(main())
