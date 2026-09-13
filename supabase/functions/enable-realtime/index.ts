// Supabase Edge Function: enable-realtime
// M2-T7 helper. Adds the persons and instructions tables to the
// `supabase_realtime` publication so the postgres_changes channel
// emits events for them. Idempotent: a second call is a no-op.
//
// Guarded by the same BOOTSTRAP_TOKEN as admin-bootstrap.
// Plan: remove this function after M2-T7 ships. The migration
// `supabase/migrations/0003_enable_realtime_publication.sql` is
// the durable record.

import { safeTokenEquals } from "../_shared/auth.ts";
import { corsHeaders, handleCors } from "../_shared/cors.ts";

Deno.serve(async (req) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // Guard: require shared secret in Authorization header.
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

  // Use the PostgREST admin RPC to run raw SQL. The `pg_execute_server_program`
  // function is not exposed by default, but we can use `exec` via the
  // supabase-js client's `rpc` on a function we know exists: there's no
  // such helper, so we run via direct REST using the `pg_meta` schema.
  //
  // The cleanest path: call a stored procedure if we have one. We don't
  // (no SECURITY DEFINER wrapper for DDL). The fallback: use the
  // `psql` connection string in Deno.env to talk to the pooler.
  //
  // Supabase exposes `DATABASE_URL` to edge functions as a connection
  // string. We can use the `postgres` npm package via Deno's node
  // compat... but edge functions prefer no node deps. Instead, we use
  // the built-in Deno PostgreSQL client: Deno.openKv doesn't help, but
  // `Deno.connect` to the TCP port + a hand-rolled Postgres wire
  // protocol is heavy.
  //
  // Simplest: use the `pg` npm package via the `npm:` specifier. Deno
  // Deploy supports it.

  // (defer to import below)
  return await runSql();
});

async function runSql() {
  // We need a SECURITY DEFINER function to do DDL. None exists, so we
  // run via direct Postgres connection from the edge function. The
  // service role has BYPASSRLS but not superuser; ALTER PUBLICATION
  // requires the table owner (postgres). We rely on the edge function's
  // direct DB connection (which uses the postgres user via DATABASE_URL).
  try {
    const { default: postgres } = await import("npm:postgres@3.4.5");
    const sql = postgres(Deno.env.get("DATABASE_URL")!);
    const result = await sql`
      do $do$
      begin
        if not exists (
          select 1 from pg_publication_tables
          where pubname = 'supabase_realtime'
            and schemaname = 'public'
            and tablename = 'persons'
        ) then
          alter publication supabase_realtime add table public.persons;
        end if;

        if not exists (
          select 1 from pg_publication_tables
          where pubname = 'supabase_realtime'
            and schemaname = 'public'
            and tablename = 'instructions'
        ) then
          alter publication supabase_realtime add table public.instructions;
        end if;
      end $do$;

      select tablename from pg_publication_tables
      where pubname = 'supabase_realtime'
        and schemaname = 'public'
        and tablename in ('persons', 'instructions')
      order by tablename;
    `;
    await sql.end();
    return new Response(
      JSON.stringify({
        ok: true,
        publication_tables: result.map((r) => r.tablename),
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  } catch {
    return new Response(
      JSON.stringify({ ok: false, error: "Realtime configuration failed" }),
      {
        status: 500,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      },
    );
  }
}
