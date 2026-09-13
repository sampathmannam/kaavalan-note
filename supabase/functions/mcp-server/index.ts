// Supabase Edge Function: Baton MCP server
//
// Speaks MCP over Streamable HTTP. Auth via Supabase JWT in Authorization
// header (verified by Supabase gateway before the handler runs).
//
// **M3 scope:** 7 resources + 4 tools. The resources are read-only
// views of the user's own data (RLS scopes everything to
// `auth.uid()`). The tools are write actions that go through the
// same RLS policies; Claude / Cursor / etc. can use them to add
// or update entries on the user's behalf.
//
// **Schema** mirrors the M0-M2 migrations:
//   - persons (id, user_id, name, designation, station, phone, ...)
//   - instructions (id, user_id, person_id, status, direction, source,
//     priority, title, raw_text, due_at, captured_at, ...)
//   - captures (id, user_id, mode, raw_text, processed, ...)
//   - events (id, user_id, instruction_id, kind, payload, ...)
//
// The handler is the M3 contract; new resources/tools are added
// to the same McpServer instance below.

import { createClient } from "@supabase/supabase-js";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { z } from "zod";
import { corsHeaders, handleCors } from "../_shared/cors.ts";

/**
 * M3: helper that turns a Supabase query error into a structured
 * MCP error response. The MCP spec says tools return `isError: true`
 * with a content array carrying a human-readable message; the
 * client surfaces that as a tool error in the chat.
 */
function mcpError(message: string) {
  return {
    isError: true,
    content: [{ type: "text" as const, text: message }],
  };
}

function operationError(operation: string) {
  return mcpError(`${operation} failed. Try again.`);
}

function resourceError(uri: URL) {
  return {
    contents: [
      {
        uri: uri.href,
        text: "Request failed. Try again.",
        mimeType: "text/plain",
      },
    ],
  };
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
    .test(value);
}

Deno.serve(async (req) => {
  const cors = handleCors(req);
  if (cors) return cors;

  // Auth: extract Supabase access token from Authorization header.
  // config.toml has `verify_jwt = true` so Supabase has already validated
  // the token before this handler runs. The header is still needed so
  // the inner Supabase client uses the user's identity (for RLS).
  const authHeader = req.headers.get("Authorization");
  if (!authHeader?.startsWith("Bearer ")) {
    return new Response("Unauthorized", {
      status: 401,
      headers: corsHeaders,
    });
  }
  const accessToken = authHeader.replace("Bearer ", "");

  // Build a Supabase client scoped to the calling user — RLS applies.
  const supabase = createClient(
    Deno.env.get("SUPABASE_URL")!,
    Deno.env.get("SUPABASE_ANON_KEY")!,
    {
      global: { headers: { Authorization: `Bearer ${accessToken}` } },
    },
  );

  const server = new McpServer({
    name: "baton-mcp",
    version: "0.4.0",
  });

  // ===========================================================================
  // RESOURCES (read-only views)
  // ===========================================================================

  // 1. baton://persons — all persons for the calling user.
  server.resource(
    "persons",
    "baton://persons",
    async (uri) => {
      const { data, error } = await supabase
        .from("persons")
        .select("id, name, designation, station, phone, created_at")
        .is("deleted_at", null)
        .order("name", { ascending: true });
      if (error) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify(data, null, 2),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 2. baton://person/{id} — one person with their full instruction
  // timeline. The MCP `resource` API is static per URI; we use a
  // template pattern with a regex so any personId resolves.
  server.resource(
    "person-detail",
    "baton://person/{personId}",
    async (uri, params) => {
      const personId = (params as { personId?: string })?.personId;
      if (!personId || !isUuid(personId)) {
        return {
          contents: [{
            uri: uri.href,
            text: "Invalid personId",
            mimeType: "text/plain",
          }],
        };
      }
      const { data: person, error: personErr } = await supabase
        .from("persons")
        .select("id, name, designation, station, phone, created_at")
        .eq("id", personId)
        .is("deleted_at", null)
        .maybeSingle();
      if (personErr) {
        return resourceError(uri);
      }
      if (!person) {
        return {
          contents: [{
            uri: uri.href,
            text: "Not found",
            mimeType: "text/plain",
          }],
        };
      }
      const { data: instructions, error: insErr } = await supabase
        .from("instructions")
        .select(
          "id, title, raw_text, status, priority, direction, due_at, captured_at",
        )
        .eq("person_id", personId)
        .is("deleted_at", null)
        .order("captured_at", { ascending: false })
        .limit(100);
      if (insErr) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify({ person, instructions }, null, 2),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 3. baton://instructions — all instructions, capped at 200.
  server.resource(
    "instructions",
    "baton://instructions",
    async (uri) => {
      const { data, error } = await supabase
        .from("instructions")
        .select(
          "id, person_id, title, raw_text, status, priority, direction, due_at, captured_at",
        )
        .is("deleted_at", null)
        .order("captured_at", { ascending: false })
        .limit(200);
      if (error) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify(data, null, 2),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 4. baton://instruction/{id} — one instruction.
  server.resource(
    "instruction-detail",
    "baton://instruction/{instructionId}",
    async (uri, params) => {
      const id = (params as { instructionId?: string })?.instructionId;
      if (!id || !isUuid(id)) {
        return {
          contents: [{
            uri: uri.href,
            text: "Invalid instructionId",
            mimeType: "text/plain",
          }],
        };
      }
      const { data, error } = await supabase
        .from("instructions")
        .select("*")
        .eq("id", id)
        .is("deleted_at", null)
        .maybeSingle();
      if (error) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: data ? JSON.stringify(data, null, 2) : "Not found",
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 5. baton://instructions/open — open + in-progress instructions
  // (status NOT IN (DONE, CARRIED_OVER, DROPPED)). The "what needs
  // you today" surface for the Today screen.
  server.resource(
    "instructions-open",
    "baton://instructions/open",
    async (uri) => {
      const { data, error } = await supabase
        .from("instructions")
        .select(
          "id, person_id, title, raw_text, status, priority, direction, due_at, captured_at",
        )
        .not("status", "in", "(DONE,CARRIED_OVER,DROPPED)")
        .is("deleted_at", null)
        .order("priority", { ascending: false })
        .order("due_at", { ascending: true, nullsFirst: false })
        .limit(100);
      if (error) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify(data, null, 2),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 6. baton://instructions/due-today — open instructions whose
  // due_at is between today 00:00 and tomorrow 00:00 (Asia/Kolkata).
  // Supabase stores `due_at` as timestamptz, so we filter in UTC.
  server.resource(
    "instructions-due-today",
    "baton://instructions/due-today",
    async (uri) => {
      // Compute the IST day boundaries in UTC. The server's TZ
      // is set to Asia/Kolkata in supabase/config.toml; this
      // expression uses the same convention.
      const nowMs = Date.now();
      const istOffsetMs = 5.5 * 60 * 60 * 1000;
      const dayMs = 24 * 60 * 60 * 1000;
      const startUtc = new Date(
        Math.floor((nowMs + istOffsetMs) / dayMs) * dayMs - istOffsetMs,
      );
      const endUtc = new Date(startUtc.getTime() + dayMs);
      const { data, error } = await supabase
        .from("instructions")
        .select(
          "id, person_id, title, raw_text, status, priority, direction, due_at, captured_at",
        )
        .not("status", "in", "(DONE,CARRIED_OVER,DROPPED)")
        .gte("due_at", startUtc.toISOString())
        .lt("due_at", endUtc.toISOString())
        .is("deleted_at", null)
        .order("due_at", { ascending: true });
      if (error) {
        return resourceError(uri);
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify(data, null, 2),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // 7. baton://stats — aggregate counts grouped by status. Useful
  // for the brief and for ad-hoc LLMs that want a one-shot snapshot.
  server.resource(
    "stats",
    "baton://stats",
    async (uri) => {
      // Single round-trip: fetch minimal columns for active
      // instructions, then aggregate in-process. The M3 row
      // counts are small (hundreds) so the in-process aggregation
      // is fast and avoids a Postgres RPC.
      const { data, error } = await supabase
        .from("instructions")
        .select("status, priority, captured_at, due_at")
        .is("deleted_at", null);
      if (error) {
        return resourceError(uri);
      }
      const byStatus: Record<string, number> = {};
      const byPriority: Record<string, number> = {};
      const nowMs = Date.now();
      const istOffsetMs = 5.5 * 60 * 60 * 1000;
      const todayIso = new Date(nowMs + istOffsetMs).toISOString().slice(0, 10);
      let dueToday = 0;
      let overdue = 0;
      for (const row of data ?? []) {
        byStatus[row.status] = (byStatus[row.status] ?? 0) + 1;
        byPriority[row.priority] = (byPriority[row.priority] ?? 0) + 1;
        if (
          row.due_at &&
          new Date(Date.parse(row.due_at) + istOffsetMs).toISOString().slice(
              0,
              10,
            ) === todayIso
        ) dueToday += 1;
        if (
          row.due_at &&
          Date.parse(row.due_at) < nowMs &&
          !["DONE", "CARRIED_OVER", "DROPPED"].includes(row.status)
        ) overdue += 1;
      }
      return {
        contents: [
          {
            uri: uri.href,
            text: JSON.stringify(
              {
                byStatus,
                byPriority,
                dueToday,
                overdue,
                total: data?.length ?? 0,
              },
              null,
              2,
            ),
            mimeType: "application/json",
          },
        ],
      };
    },
  );

  // ===========================================================================
  // TOOLS (write actions)
  // ===========================================================================

  // T1. create_person — adds a new person. The unique constraint
  // (user_id, name, designation, station) is enforced by Supabase;
  // a duplicate insert returns a Postgres 23505 which we surface
  // as a tool error.
  server.tool(
    "create_person",
    "Create a new person in the user's Baton address book. Returns the new person id.",
    {
      name: z.string().trim().min(1).max(160).describe(
        "The person's full name, e.g. 'SHO Ramu'",
      ),
      designation: z.string().trim().max(120).optional().describe(
        "Rank or post, e.g. 'SHO', 'DSP', 'SP'",
      ),
      station: z.string().trim().max(160).optional().describe(
        "Police station, e.g. 'Bandipora'",
      ),
      phone: z.string().trim().max(32).optional().describe(
        "Phone number, e.g. '+91 98765 43210'",
      ),
    },
    async ({ name, designation, station, phone }) => {
      const { data, error } = await supabase
        .from("persons")
        .insert({
          name,
          designation: designation ?? null,
          station: station ?? null,
          phone: phone ?? null,
        })
        .select("id")
        .single();
      if (error) {
        return operationError("create_person");
      }
      return {
        content: [
          { type: "text" as const, text: `Created person id=${data.id}` },
        ],
      };
    },
  );

  // T2. create_instruction — adds a new instruction. The M3 client
  // already does the on-device LLM extraction; this tool is the
  // path Claude / Cursor use to add a row from a chat message.
  server.tool(
    "create_instruction",
    "Create a new instruction. Returns the new instruction id.",
    {
      title: z.string().trim().min(1).max(200).describe(
        "5-7 word human-readable label",
      ),
      raw_text: z.string().trim().min(1).max(20_000).describe(
        "The full instruction verbatim",
      ),
      person_name: z.string().trim().max(160).optional().describe(
        "Person the instruction is about. If a person with this exact name exists for the user, the instruction is linked. Otherwise a new person is auto-created.",
      ),
      due_at: z.string().datetime({ offset: true }).optional().describe(
        "ISO 8601 timestamp, e.g. '2026-08-15T17:00:00+05:30'",
      ),
      priority: z.enum(["LOW", "NORMAL", "HIGH"]).optional().default("NORMAL"),
      direction: z.enum(["INCOMING", "OUTGOING", "SELF"]).optional().default(
        "OUTGOING",
      ),
    },
    async ({ title, raw_text, person_name, due_at, priority, direction }) => {
      // Resolve the person. If a name is given and a row exists
      // with the same name, link to that; otherwise create the
      // person on-the-fly. This matches the M1-T5 capture flow
      // and keeps the tool useful for ad-hoc "Tell SHO Ramu..."
      // calls from the user's chat assistant.
      let personId: string | null = null;
      if (person_name) {
        const { data: existing, error: lookupError } = await supabase
          .from("persons")
          .select("id")
          .eq("name", person_name)
          .is("deleted_at", null)
          .limit(1)
          .maybeSingle();
        if (lookupError) {
          return operationError("create_instruction");
        }
        if (existing) {
          personId = existing.id;
        } else {
          const { data: created, error: personErr } = await supabase
            .from("persons")
            .insert({ name: person_name })
            .select("id")
            .single();
          if (personErr) {
            return operationError("create_instruction");
          }
          personId = created.id;
        }
      }
      const { data, error } = await supabase
        .from("instructions")
        .insert({
          person_id: personId,
          title,
          raw_text,
          due_at: due_at ?? null,
          priority: priority ?? "NORMAL",
          direction: direction ?? "OUTGOING",
          source: "MCP",
          status: "OPEN",
          captured_at: new Date().toISOString(),
        })
        .select("id")
        .single();
      if (error) {
        return operationError("create_instruction");
      }
      return {
        content: [
          {
            type: "text" as const,
            text: personId
              ? `Created instruction id=${data.id} for person_id=${personId}`
              : `Created instruction id=${data.id} (no person linked)`,
          },
        ],
      };
    },
  );

  // T3. update_instruction_status — moves a row through the
  // status enum (OPEN -> IN_PROGRESS -> DONE etc.). The M4
  // nudge flow will do this automatically; this tool is the
  // manual override path.
  server.tool(
    "update_instruction_status",
    "Update an instruction's status. Use the standard Baton statuses.",
    {
      id: z.string().uuid().describe("The instruction's UUID"),
      status: z.enum([
        "OPEN",
        "ACK_PENDING",
        "IN_PROGRESS",
        "WAITING_ON_OTHER",
        "DONE",
        "CARRIED_OVER",
        "DROPPED",
      ]).describe("The new status"),
    },
    async ({ id, status }) => {
      const update: Record<string, unknown> = { status };
      if (status === "DONE") update.completed_at = new Date().toISOString();
      const { error } = await supabase
        .from("instructions")
        .update(update)
        .eq("id", id);
      if (error) {
        return operationError("update_instruction_status");
      }
      return {
        content: [
          {
            type: "text" as const,
            text: `Updated instruction id=${id} status=${status}`,
          },
        ],
      };
    },
  );

  // T4. search_instructions — substring match on title / raw_text.
  // Postgres `ilike` is fine for the v1 row counts (hundreds); a
  // tsvector + GIN index lands in v1.1 when the dataset grows.
  server.tool(
    "search_instructions",
    "Search the user's instructions by free-text query. Returns up to 50 matches.",
    {
      query: z.string().trim().min(1).max(200).describe(
        "Free-text query, matched against title and raw_text",
      ),
    },
    async ({ query }) => {
      const like = `%${query.replace(/[\\%_]/g, String.raw`\$&`)}%`;
      const fields =
        "id, person_id, title, raw_text, status, priority, direction, due_at, captured_at";
      const [titleResult, textResult] = await Promise.all([
        supabase.from("instructions").select(fields).ilike("title", like)
          .is("deleted_at", null).order("captured_at", { ascending: false })
          .limit(50),
        supabase.from("instructions").select(fields).ilike("raw_text", like)
          .is("deleted_at", null).order("captured_at", { ascending: false })
          .limit(50),
      ]);
      if (titleResult.error || textResult.error) {
        return operationError("search_instructions");
      }
      const data = [...(titleResult.data ?? []), ...(textResult.data ?? [])]
        .filter((row, index, rows) =>
          rows.findIndex((candidate) => candidate.id === row.id) === index
        )
        .sort((left, right) =>
          Date.parse(right.captured_at) - Date.parse(left.captured_at)
        )
        .slice(0, 50);
      return {
        content: [
          {
            type: "text" as const,
            text: JSON.stringify(data, null, 2),
          },
        ],
      };
    },
  );

  // T5. draft_nudge — server-side nudge draft generator (M4).
  // The on-device LLM is the production path; this is the cloud
  // fallback for when the user drafts from a desktop chat client.
  // The template is intentionally plain. The on-device path
  // rewrites it in the user's voice; this tool returns the
  // server-side template, suitable for further LLM refinement
  // on the client side.
  server.tool(
    "draft_nudge",
    "Generate a nudge draft for an OUTGOING instruction that's gone quiet. The on-device LLM refines the template; this is the cloud-side baseline.",
    {
      instruction_id: z.string().uuid().describe("The instruction's UUID"),
      tone: z.enum(["polite", "urgent", "casual"]).optional().default("polite")
        .describe(
          "The draft tone. polite is the default; the user's voice in the on-device path overrides this.",
        ),
    },
    async ({ instruction_id, tone }) => {
      // Fetch the instruction + person for context.
      const { data: ins, error: insErr } = await supabase
        .from("instructions")
        .select(
          "id, title, raw_text, status, direction, due_at, captured_at, person_id",
        )
        .eq("id", instruction_id)
        .is("deleted_at", null)
        .maybeSingle();
      if (insErr) {
        return operationError("draft_nudge");
      }
      if (!ins) {
        return mcpError(`draft_nudge: instruction ${instruction_id} not found`);
      }
      if (ins.direction !== "OUTGOING") {
        return mcpError(
          `draft_nudge: instruction ${instruction_id} is ${ins.direction}; only OUTGOING instructions are eligible for nudges`,
        );
      }
      // Resolve the person name.
      let personName: string | null = null;
      if (ins.person_id) {
        const { data: person } = await supabase
          .from("persons")
          .select("name")
          .eq("id", ins.person_id)
          .maybeSingle();
        personName = person?.name ?? null;
      }
      // Build the draft per tone.
      const name = personName?.trim() || "there";
      const title = (ins.title || ins.raw_text || "").slice(0, 100);
      let draftText: string;
      switch (tone) {
        case "urgent":
          draftText =
            `${name} — I need the "${title}" by end of day. Let me know what's blocking.`;
          break;
        case "casual":
          draftText =
            `Hey ${name}, gentle reminder on the "${title}" — any update when you get a moment?`;
          break;
        case "polite":
        default:
          draftText =
            `Hi ${name} — following up on "${title}". Let me know if you need anything from me.`;
          break;
      }
      // Persist a nudge_drafts row so the user's local mirror
      // (and the on-device sheet) sees the draft on next sync.
      // The RLS policy on nudge_drafts restricts to the calling
      // user, so this insert is safe.
      await supabase
        .from("nudge_drafts")
        .insert({
          instruction_id,
          draft_text: draftText,
          status: "DRAFT",
        });
      // Non-fatal: if the local mirror doesn't have nudge_drafts
      // wired in v1 the insert errors; we still return the draft
      // text to the caller.
      return {
        content: [
          {
            type: "text" as const,
            text: JSON.stringify(
              {
                instruction_id,
                person_name: personName,
                tone,
                draft_text: draftText,
              },
              null,
              2,
            ),
          },
        ],
      };
    },
  );

  // Web-standard Streamable HTTP transport (Deno / Supabase Edge / Cloudflare).
  // The non-web `StreamableHTTPServerTransport` from `server/streamableHttp.js`
  // is built around `http.IncomingMessage` + `http.ServerResponse` and crashes
  // on the Deno runtime with `Cannot read properties of undefined (reading 'on')`.
  // `WebStandardStreamableHTTPServerTransport` accepts the native
  // `Request` / `Response` objects we already have.
  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
  });
  await server.connect(transport);

  return transport.handleRequest(req);
});
