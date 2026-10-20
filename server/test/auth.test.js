import assert from "node:assert/strict";
import test from "node:test";

import { bearerTokenFromHeader } from "../src/auth.js";

test("extracts bearer tokens without accepting other authorization schemes", () => {
  assert.equal(bearerTokenFromHeader("Bearer abc.def.ghi"), "abc.def.ghi");
  assert.equal(bearerTokenFromHeader("bearer token"), "token");
  assert.equal(bearerTokenFromHeader("Basic abc"), null);
  assert.equal(bearerTokenFromHeader(""), null);
});
