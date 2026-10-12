# AIemulator proxy

AIemulator, the assistant in AEmulator Super Plus, talks to Claude through this small Cloudflare Worker. The Worker holds
your Anthropic API key, so the key never ships inside the APK, where anyone could pull it out.

What it does for every request from the app:

- checks the access code (if you set any), sent by the app in place of an API key
- pins the model (`claude-opus-5-5`) and caps `max_tokens`, whatever the app asks for
- passes through only the request fields AIemulator uses, and limits effort to low/medium/high
- turns on server-side refusal fallbacks (`fallbacks: "default"`)
- adds your real API key and streams Anthropic's answer straight back

The agent loop and its tools run in the app, because that's where the firmware list, settings and logs are.

## Set it up (about 10 minutes, free Cloudflare plan)

1. Create an API key at <https://platform.claude.com> and **set a monthly spend limit** there. The limit is your real
   protection: an access code can be shared on, and anything inside an APK can be extracted.
2. Install Node.js 20+, then in this folder:

   ```bash
   npm install
   npx wrangler login
   npx wrangler secret put ANTHROPIC_API_KEY
   npx wrangler secret put ACCESS_CODES
   npx wrangler deploy
   ```

   For `ACCESS_CODES`, enter one or more codes separated by commas (`me-7f3k,friend-x92p`). Give each person their own,
   so you can remove one later without changing the others. Leave it unset only if anyone with the URL may use your key.
3. `wrangler deploy` prints the address, such as `https://aiemulator.<you>.workers.dev`.
4. In the app: library screen → ✨ AIemulator → server icon → paste the address and your access code.

To give every build a default address, build the APK with `-PaiemuServerUrl=https://aiemulator.<you>.workers.dev`.
Users then only type their access code.

## Settings

| Name | Where | Default | Meaning |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | secret | none | Your Anthropic API key |
| `ACCESS_CODES` | secret | none (open) | Comma-separated codes the app must send |
| `MODEL` | `wrangler.toml` | `claude-opus-5-5` | Model every request runs on |
| `MAX_TOKENS` | `wrangler.toml` | `64000` | Upper bound on one answer's length |

Revoke a code: run `npx wrangler secret put ACCESS_CODES` again without it. Usage and cost show up in the Claude
Console under your API key.

## Try it locally

Create `.dev.vars` (it's git-ignored):

```
ANTHROPIC_API_KEY=sk-ant-...
ACCESS_CODES=test-code
```

Then run `npm run dev` and point the app (or curl) at `http://<your PC's IP>:8787`.
