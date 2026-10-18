// ============================================================================
// SmartEnergy Hub - initialisation MongoDB  -  CDC sections 6.2 et 6.3
// Execute automatiquement par l'image mongo AU PREMIER DEMARRAGE (volume vide), en root.
// Une base logique par service, un utilisateur par base (principe du moindre privilege).
// Pour re-jouer : docker compose down -v
// ============================================================================

const sensorPwd = process.env.MONGO_SENSOR_PASSWORD;
const analyticsPwd = process.env.MONGO_ANALYTICS_PASSWORD;

// ------------------------------------------------------------------ sensor_db
const sensorDb = db.getSiblingDB('sensor_db');
sensorDb.createUser({ user: 'sensor_app', pwd: sensorPwd, roles: [{ role: 'readWrite', db: 'sensor_db' }] });
sensorDb.createCollection('sensors');
sensorDb.sensors.createIndex({ sensorId: 1 }, { unique: true, name: 'uk_sensorId' });
sensorDb.sensors.createIndex({ building: 1 }, { name: 'ix_building' });

// -------------------------------------------------------------- analytics_db
const analyticsDb = db.getSiblingDB('analytics_db');
analyticsDb.createUser({ user: 'analytics_app', pwd: analyticsPwd, roles: [{ role: 'readWrite', db: 'analytics_db' }] });

// sensor_state : _id = sensorId (un document par capteur)
analyticsDb.createCollection('sensor_state');
analyticsDb.sensor_state.createIndex({ lastPowerKw: 1 }, { name: 'ix_lastPowerKw' });

// hourly_aggregates : _id = "<sensorId>|yyyy-MM-ddTHH"
analyticsDb.createCollection('hourly_aggregates');
analyticsDb.hourly_aggregates.createIndex({ sensorId: 1, hour: 1 }, { name: 'ix_sensor_hour' });

// daily_aggregates : _id = "<sensorId>|yyyy-MM-dd"
analyticsDb.createCollection('daily_aggregates');
analyticsDb.daily_aggregates.createIndex({ sensorId: 1, date: 1 }, { unique: true, name: 'uk_sensor_date' });
analyticsDb.daily_aggregates.createIndex({ maxPowerKw: 1 }, { name: 'ix_maxPowerKw' });

// anomalies : unicite (eventId, type) = idempotence des insertions
analyticsDb.createCollection('anomalies');
analyticsDb.anomalies.createIndex({ eventId: 1, type: 1 }, { unique: true, name: 'uk_event_type' });
analyticsDb.anomalies.createIndex({ sensorId: 1, timestamp: -1 }, { name: 'ix_sensor_ts' });
// Retention optionnelle (90 jours) : decommenter et utiliser un champ Date "timestamp"
// analyticsDb.anomalies.createIndex({ timestamp: 1 }, { expireAfterSeconds: 7776000, name: 'ttl_anomalies' });

// forecasts (extension IA)
analyticsDb.createCollection('forecasts');
analyticsDb.forecasts.createIndex({ sensorId: 1, generatedAt: -1 }, { name: 'ix_sensor_generated' });

print('SmartEnergy Hub : bases sensor_db et analytics_db initialisees.');
