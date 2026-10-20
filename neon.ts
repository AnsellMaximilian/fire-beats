import { defineConfig } from "@neon/config/v1";

export default defineConfig({
  // Managed Better Auth will own user identities and sessions. Application
  // routes still need to verify the user's token and authorize every resource.
  auth: true,

  // Keep user-generated files separate by purpose. Tracks and application
  // images are private; callers receive short-lived, authorized URLs. The
  // existing avatars bucket remains public-readable for now so this policy
  // matches the live production branch without changing its access mode.
  buckets: {
    avatars: { access: "public_read" },
    "default-tracks": { access: "public_read" },
    images: { access: "private" },
    tracks: { access: "private" },
  },

  // The signaling server remains a long-running service on Render. Add the
  // audio-analysis worker here once its storage trigger and database writes
  // are implemented, rather than deploying a demo function.
  functions: {},

  // Temporary development branches expire automatically. The production
  // branch and existing branches retain their current compute settings.
  branch: (branch) => {
    if (branch.isDefault) {
      return {};
    }
    if (!branch.exists) {
      return { ttl: "7d" };
    }
    return {};
  },
});
