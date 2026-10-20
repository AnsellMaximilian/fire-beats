# Default tracks

Put MP3, WAV, M4A, AAC, or OGG files in this directory, then run:

```sh
npm run seed:defaults
```

The command analyzes each file, uploads it to the public `default-tracks`
bucket, and upserts its metadata in Postgres. Audio files are intentionally
ignored by Git; only add music you have permission to distribute.
