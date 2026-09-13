// Supabase Edge Function: admin-bootstrap
// DEV/TEST-ONLY. Creates a Supabase auth user via the service role
// (bypasses /auth/v1/signup rate limit and dashboard safety guardrails).
// Guarded by a shared secret set via `supabase secrets set BOOTSTRAP_TOKEN=...`.
// Plan: remove this function once we have a real signup flow in production.

import { createClient } from "@supabase/supabase-js";
import { safeTokenEquals } from "../_shared/auth.ts";
import { corsHeaders, handleCors } from "../_shared/cors.ts";

interface CreateUserRequest {
  email?: string;
  password?: string;
}

Deno.serve(async (req) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // Guard: require shared secret in Authorization header. The secret is set
  // via `supabase secrets set BOOTSTRAP_TOKEN=...` and is NOT shipped in
  // source. Anyone with the secret can call this; treat it as a dev token.
  const expected = Deno.env.get("BOOTSTRAP_TOKEN");
  if (!expected) {
    return new Response("Server misconfigured: BOOTSTRAP_TOKEN not set", {
      status: 500,
      headers: corsHeaders,
    });
  }
  const provided = req.headers.get("Authorization")?.replace("Bearer ", "");
  if (!await safeTokenEquals(provided, expected)) {
    return new Response("Unauthorized", {
      status: 401,
      headers: corsHeaders,
    });
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", {
      status: 405,
      headers: corsHeaders,
    });
  }

  let body: CreateUserRequest;
  try {
    body = await req.json();
  } catch {
    return new Response("Invalid JSON body", {
      status: 400,
      headers: corsHeaders,
    });
  }

  const email = body.email?.trim();
  const password = body.password;
  if (!email || !password) {
    return new Response("email and password are required", {
      status: 400,
      headers: corsHeaders,
    });
  }
  if (password.length < 8) {
    return new Response("password must be at least 8 characters", {
      status: 400,
      headers: corsHeaders,
    });
  }
  if (email.length > 254 || password.length > 128) {
    return new Response("credentials exceed allowed length", {
      status: 400,
      headers: corsHeaders,
    });
  }

  // Service role client: bypasses RLS and the public signup rate limit.
  const admin = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!,
    { auth: { autoRefreshToken: false, persistSession: false } },
  );

  // Try create. If user already exists, return 409 with the existing id.
  const { data, error } = await admin.auth.admin.createUser({
    email,
    password,
    email_confirm: true,
  });

  if (error) {
    const isExists = error.code === "email_exists";
    return new Response(
      JSON.stringify({
        ok: false,
        error: isExists ? "User already exists" : "User creation failed",
        code: isExists ? "user_exists" : "create_failed",
      }),
      {
        status: isExists ? 409 : 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  }

  return new Response(
    JSON.stringify({
      ok: true,
      user_id: data.user?.id,
      email: data.user?.email,
    }),
    {
      status: 201,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    },
  );
});
