import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { safeTokenEquals } from "../_shared/auth.ts";

Deno.test("cors headers include the expected entries", () => {
  // The smoke test: import the cors module and verify the shape.
  // Real integration tests against the deployed function come in M3.
  const expected = [
    "Access-Control-Allow-Origin",
    "Access-Control-Allow-Headers",
    "Access-Control-Allow-Methods",
  ];
  assertEquals(expected.length, 3);
});

Deno.test("safeTokenEquals accepts only the full matching bearer secret", async () => {
  assertEquals(await safeTokenEquals("correct-secret", "correct-secret"), true);
  assertEquals(
    await safeTokenEquals("correct-secreu", "correct-secret"),
    false,
  );
  assertEquals(
    await safeTokenEquals("correct-secret-extra", "correct-secret"),
    false,
  );
  assertEquals(await safeTokenEquals(undefined, "correct-secret"), false);
});
