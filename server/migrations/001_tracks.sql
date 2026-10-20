CREATE TABLE IF NOT EXISTS tracks (
  id text PRIMARY KEY CHECK (id ~ '^[a-f0-9]{16}$'),
  owner_user_id text,
  visibility text NOT NULL CHECK (visibility IN ('public', 'private')),
  source text NOT NULL CHECK (source IN ('default', 'upload')),
  name varchar(160) NOT NULL,
  bucket text NOT NULL,
  object_key text NOT NULL UNIQUE,
  mime_type varchar(100) NOT NULL,
  size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
  status text NOT NULL CHECK (status IN ('uploading', 'analyzing', 'ready', 'error')),
  progress integer NOT NULL DEFAULT 0 CHECK (progress BETWEEN 0 AND 100),
  bpm double precision,
  duration double precision,
  beats jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(beats) = 'array'),
  error text,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT tracks_ownership_check CHECK (
    (source = 'default' AND visibility = 'public' AND owner_user_id IS NULL)
    OR
    (source = 'upload' AND visibility = 'private' AND owner_user_id IS NOT NULL)
  )
);

CREATE INDEX IF NOT EXISTS tracks_public_ready_idx
  ON tracks (created_at DESC)
  WHERE visibility = 'public' AND status = 'ready';

CREATE INDEX IF NOT EXISTS tracks_owner_created_idx
  ON tracks (owner_user_id, created_at DESC)
  WHERE owner_user_id IS NOT NULL;
