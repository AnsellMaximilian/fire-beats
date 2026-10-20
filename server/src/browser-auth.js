import { createAuthClient } from "@neondatabase/auth";

let client = null;

function initialize(baseUrl) {
  client = baseUrl ? createAuthClient(baseUrl) : null;
  return Boolean(client);
}

function requireClient() {
  if (!client) throw new Error("Authentication is not configured");
  return client;
}

async function getSession() {
  if (!client) return { user: null, session: null };
  const result = await client.getSession();
  if (result.error) throw result.error;
  const session = result.data?.session || null;
  return { session, user: result.data?.user || session?.user || null };
}

async function getToken() {
  if (!client) return null;
  const result = await client.token();
  if (result.error) throw result.error;
  return result.data?.token || null;
}

async function signInWithGoogle(callbackURL) {
  return requireClient().signIn.social({ provider: "google", callbackURL });
}

async function signOut() {
  if (!client) return;
  const result = await client.signOut();
  if (result?.error) throw result.error;
}

window.fireBeatsAuth = { initialize, getSession, getToken, signInWithGoogle, signOut };
