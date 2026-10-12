/* AEmulator Super Plus, 2026-10-12: AIemulator proxy. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * The app talks to Claude through this Worker so the Anthropic API key never ships inside the APK.
 * The Worker is a thin, transparent reverse proxy for POST /v1/messages: the app's Anthropic SDK builds the
 * requests and parses the (streamed) responses; here we only check the access code, pin the model and limits,
 * drop request fields the app does not need, opt into refusal fallbacks, and add the real key.
 */

export interface Env {
  /** Secret: your Anthropic API key (`wrangler secret put ANTHROPIC_API_KEY`). */
  ANTHROPIC_API_KEY: string;
  /** Secret, optional: comma-separated access codes the app must send. Empty = anyone with the URL can use it. */
  ACCESS_CODES?: string;
  /** Model every request runs on, whatever the app asks for. */
  MODEL?: string;
  /** Upper bound for max_tokens. */
  MAX_TOKENS?: string;
}

const UPSTREAM = "https://api.anthropic.com/v1/messages";
const MAX_BODY_BYTES = 2 * 1024 * 1024;
// everything the app sends; anything else (other betas, server tools, speed, ...) is dropped
const ALLOWED_FIELDS = ["model", "max_tokens", "system", "messages", "tools", "tool_choice", "stream", "thinking", "output_config", "cache_control"];
const ALLOWED_EFFORT = ["low", "medium", "high"];

function error(status: number, type: string, message: string): Response {
  // same shape as the Anthropic API, so the app's SDK raises the matching typed exception
  return Response.json({ type: "error", error: { type, message } }, { status });
}

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname !== "/v1/messages") return error(404, "not_found_error", "Only /v1/messages is served here.");
    if (request.method !== "POST") return error(405, "invalid_request_error", "Use POST.");

    const codes = (env.ACCESS_CODES ?? "").split(",").map((c) => c.trim()).filter(Boolean);
    if (codes.length > 0 && !codes.includes(request.headers.get("x-api-key") ?? "")) {
      return error(401, "authentication_error", "Unknown AIemulator access code.");
    }

    const raw = await request.text();
    if (raw.length > MAX_BODY_BYTES) return error(413, "request_too_large", "Conversation too long; start a new chat.");
    let body: Record<string, unknown>;
    try {
      body = JSON.parse(raw);
    } catch {
      return error(400, "invalid_request_error", "Body is not JSON.");
    }

    const out: Record<string, unknown> = {};
    for (const key of ALLOWED_FIELDS) if (key in body) out[key] = body[key];
    out.model = env.MODEL || "claude-opus-5-5";
    const cap = Number(env.MAX_TOKENS || 64000);
    out.max_tokens = Math.min(Number(body.max_tokens) || cap, cap);
    const effort = (body.output_config as { effort?: string } | undefined)?.effort;
    out.output_config = { effort: ALLOWED_EFFORT.includes(effort ?? "") ? effort : "medium" };
    // a declined request is retried server-side on the model Anthropic recommends for that refusal category
    out.fallbacks = "default";

    const upstream = await fetch(UPSTREAM, {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "x-api-key": env.ANTHROPIC_API_KEY,
        "anthropic-version": request.headers.get("anthropic-version") ?? "2023-06-01",
        "anthropic-beta": "server-side-fallback-2026-07-01",
      },
      body: JSON.stringify(out),
    });

    // stream the answer (SSE) or the error straight back; keep the headers the SDK reads for retries
    const headers = new Headers();
    for (const name of ["content-type", "request-id", "retry-after", "x-should-retry"]) {
      const value = upstream.headers.get(name);
      if (value) headers.set(name, value);
    }
    return new Response(upstream.body, { status: upstream.status, headers });
  },
};
