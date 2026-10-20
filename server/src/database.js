import { postgresConnectionString } from "./environment.js";

import pg from "pg";

const { Pool } = pg;
let pool;

function database() {
  if (!pool) {
    pool = new Pool({
      connectionString: postgresConnectionString("DATABASE_URL"),
      max: Number(process.env.DATABASE_POOL_SIZE || 5),
      idleTimeoutMillis: 30_000,
      connectionTimeoutMillis: 10_000,
    });
    pool.on("error", (error) => console.error("Unexpected Postgres pool error", error));
  }
  return pool;
}

function mapTrack(row) {
  if (!row) return null;
  return {
    id: row.id,
    ownerUserId: row.owner_user_id,
    visibility: row.visibility,
    source: row.source,
    name: row.name,
    bucket: row.bucket,
    objectKey: row.object_key,
    mimeType: row.mime_type,
    sizeBytes: Number(row.size_bytes),
    status: row.status,
    progress: row.progress,
    bpm: row.bpm === null ? null : Number(row.bpm),
    duration: row.duration === null ? null : Number(row.duration),
    beats: row.beats || [],
    error: row.error,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export async function listVisibleTracks(userId) {
  const result = await database().query(
    `SELECT * FROM tracks
       WHERE visibility = 'public'
          OR ($1::text IS NOT NULL AND owner_user_id = $1)
       ORDER BY (source = 'default') DESC, created_at DESC`,
    [userId || null],
  );
  return result.rows.map(mapTrack);
}

export async function findVisibleTrack(id, userId) {
  const result = await database().query(
    `SELECT * FROM tracks
       WHERE id = $1
         AND (visibility = 'public'
              OR ($2::text IS NOT NULL AND owner_user_id = $2))`,
    [id, userId || null],
  );
  return mapTrack(result.rows[0]);
}

export async function findTrack(id) {
  const result = await database().query("SELECT * FROM tracks WHERE id = $1", [id]);
  return mapTrack(result.rows[0]);
}

export async function createUploadedTrack(track) {
  const result = await database().query(
    `INSERT INTO tracks
       (id, owner_user_id, visibility, source, name, bucket, object_key,
        mime_type, size_bytes, status, progress)
     VALUES ($1, $2, 'private', 'upload', $3, $4, $5, $6, $7, 'uploading', 0)
     RETURNING *`,
    [
      track.id,
      track.ownerUserId,
      track.name,
      track.bucket,
      track.objectKey,
      track.mimeType,
      track.sizeBytes,
    ],
  );
  return mapTrack(result.rows[0]);
}

export async function setTrackProgress(id, status, progress, error = null) {
  const result = await database().query(
    `UPDATE tracks
        SET status = $2, progress = $3, error = $4, updated_at = now()
      WHERE id = $1
      RETURNING *`,
    [id, status, progress, error],
  );
  return mapTrack(result.rows[0]);
}

export async function completeTrackAnalysis(id, analysis) {
  const result = await database().query(
    `UPDATE tracks
        SET status = 'ready', progress = 100, bpm = $2, duration = $3,
            beats = $4::jsonb, error = NULL, updated_at = now()
      WHERE id = $1
      RETURNING *`,
    [id, analysis.bpm, analysis.duration, JSON.stringify(analysis.beats)],
  );
  return mapTrack(result.rows[0]);
}

export async function markInterruptedUploads() {
  await database().query(
    `UPDATE tracks
        SET status = 'error', error = 'Upload interrupted; please try again', updated_at = now()
      WHERE status = 'uploading'`,
  );
}

export async function tracksNeedingAnalysis() {
  const result = await database().query(
    "SELECT * FROM tracks WHERE status = 'analyzing' ORDER BY created_at",
  );
  return result.rows.map(mapTrack);
}

export async function upsertDefaultTrack(track) {
  const result = await database().query(
    `INSERT INTO tracks
       (id, owner_user_id, visibility, source, name, bucket, object_key,
        mime_type, size_bytes, status, progress, bpm, duration, beats, error)
     VALUES ($1, NULL, 'public', 'default', $2, $3, $4, $5, $6,
             'ready', 100, $7, $8, $9::jsonb, NULL)
     ON CONFLICT (id) DO UPDATE SET
       name = EXCLUDED.name,
       bucket = EXCLUDED.bucket,
       object_key = EXCLUDED.object_key,
       mime_type = EXCLUDED.mime_type,
       size_bytes = EXCLUDED.size_bytes,
       status = 'ready',
       progress = 100,
       bpm = EXCLUDED.bpm,
       duration = EXCLUDED.duration,
       beats = EXCLUDED.beats,
       error = NULL,
       updated_at = now()
     RETURNING *`,
    [
      track.id,
      track.name,
      track.bucket,
      track.objectKey,
      track.mimeType,
      track.sizeBytes,
      track.bpm,
      track.duration,
      JSON.stringify(track.beats),
    ],
  );
  return mapTrack(result.rows[0]);
}

export async function closeDatabase() {
  if (pool) {
    const current = pool;
    pool = undefined;
    await current.end();
  }
}

export async function checkDatabase() {
  const result = await database().query(
    "SELECT current_database() AS database, to_regclass('public.tracks')::text AS tracks_table, now() AS now",
  );
  return result.rows[0];
}
