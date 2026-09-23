# Notes

- User preference: minimalist theme, single HTML file, hosted at
  `a.yash0.in/whisperlow`.
- `a.yash0.in` is a custom domain on the Cloudflare Worker
  `openstream-quality-report`. The worker is now code-based (was static
  assets): it serves `/` (redirect), `/openstream/` (audit) and `/whisperlow`
  (lesson 001) from the `whisperlow-lessons` KV namespace
  (`e56506030c8f44be941b77c2de880c4d`).
- KV keys: `lesson-001` (19139 bytes), `root-redirect` (482), `openstream-audit`
  (13911) — all hash-verified byte-identical on upload. Staging keys
  (`audit-l00..`, `audit-s*`, `audit-q*`, `audit-tail`) can be deleted.
- Teaching style: one tangible win per lesson, cite file:line, end with a
  quiz + invitation to ask follow-ups.
- Tooling lesson: `cloudflare_execute` unescapes backslashes in `code` once —
  write `\\r\\n` for CRLF, or avoid backslashes entirely (base64 payloads +
  `String.fromCharCode`). Large pastes need hash verification before any PUT.
