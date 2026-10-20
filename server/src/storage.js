import "./environment.js";

import {
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  PutObjectCommand,
  S3Client,
} from "@aws-sdk/client-s3";
import { getSignedUrl } from "@aws-sdk/s3-request-presigner";

let client;

function storage() {
  if (!client) {
    if (!process.env.AWS_ENDPOINT_URL_S3) throw new Error("AWS_ENDPOINT_URL_S3 is required");
    client = new S3Client({
      endpoint: process.env.AWS_ENDPOINT_URL_S3,
      region: process.env.AWS_REGION,
      forcePathStyle: true,
    });
  }
  return client;
}

export const userTracksBucket = process.env.TRACKS_BUCKET || "tracks";
export const defaultTracksBucket = process.env.DEFAULT_TRACKS_BUCKET || "default-tracks";

export async function uploadObject({ bucket, key, body, contentLength, contentType, publicRead = false }) {
  await storage().send(
    new PutObjectCommand({
      Bucket: bucket,
      Key: key,
      Body: body,
      ContentLength: contentLength,
      ContentType: contentType,
      CacheControl: publicRead ? "public, max-age=3600" : "private, no-store",
    }),
  );
}

export async function downloadObject(bucket, key) {
  const response = await storage().send(new GetObjectCommand({ Bucket: bucket, Key: key }));
  if (!response.Body) throw new Error("Stored object has no body");
  return Buffer.from(await response.Body.transformToByteArray());
}

export async function deleteObject(bucket, key) {
  await storage().send(new DeleteObjectCommand({ Bucket: bucket, Key: key }));
}

export async function objectExists(bucket, key) {
  try {
    await storage().send(new HeadObjectCommand({ Bucket: bucket, Key: key }));
    return true;
  } catch (error) {
    if (error?.$metadata?.httpStatusCode === 404 || error?.name === "NotFound") return false;
    throw error;
  }
}

export async function signedObjectUrl(bucket, key, expiresIn = 7_200) {
  return getSignedUrl(storage(), new GetObjectCommand({ Bucket: bucket, Key: key }), { expiresIn });
}

export function publicObjectUrl(bucket, key) {
  const base = String(process.env.AWS_ENDPOINT_URL_S3 || "").replace(/\/$/, "");
  const encodedKey = key.split("/").map(encodeURIComponent).join("/");
  return `${base}/${encodeURIComponent(bucket)}/${encodedKey}`;
}
