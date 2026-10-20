import "./environment.js";

import { createRemoteJWKSet, jwtVerify } from "jose";

export class UnauthorizedError extends Error {
  constructor(message = "Authentication required") {
    super(message);
    this.name = "UnauthorizedError";
  }
}

let verifier;
let issuer;

function authVerifier() {
  const jwksUrl = process.env.NEON_AUTH_JWKS_URL;
  const baseUrl = process.env.NEON_AUTH_BASE_URL;
  if (!jwksUrl || !baseUrl) {
    throw new Error("Neon Auth is not configured");
  }
  if (!verifier) {
    verifier = createRemoteJWKSet(new URL(jwksUrl));
    issuer = new URL(baseUrl).origin;
  }
  return verifier;
}

export function bearerTokenFromHeader(value) {
  if (!value) return null;
  const match = /^Bearer\s+(.+)$/i.exec(String(value).trim());
  return match?.[1] || null;
}

export async function verifyAccessToken(token) {
  if (!token || typeof token !== "string") {
    throw new UnauthorizedError();
  }
  try {
    const { payload } = await jwtVerify(token, authVerifier(), { issuer });
    if (!payload.sub) throw new Error("Token has no subject");
    return { userId: payload.sub, payload };
  } catch (error) {
    if (error instanceof UnauthorizedError) throw error;
    throw new UnauthorizedError("Invalid or expired access token");
  }
}

export async function optionalUserFromRequest(request) {
  const header = request.headers.authorization;
  if (!header) return null;
  const token = bearerTokenFromHeader(header);
  if (!token) throw new UnauthorizedError("Malformed Authorization header");
  return verifyAccessToken(token);
}

export function publicAuthConfiguration() {
  return { authBaseUrl: process.env.NEON_AUTH_BASE_URL || null };
}
