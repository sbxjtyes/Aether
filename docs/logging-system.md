# Aether Logging System

## Purpose

Aether uses `AetherLog` as the single logging entry point for application diagnostics. The logging system is designed to improve observability while preventing secrets, user content, local paths, and raw provider responses from leaking into logcat or diagnostic exports.

## Core API

- `AetherLog.d(tag, message, throwable)` for debug-only diagnostics.
- `AetherLog.i(tag, message, throwable)` for debug-only informational state changes.
- `AetherLog.w(tag, message, throwable)` for warnings retained in all builds.
- `AetherLog.e(tag, message, throwable)` for errors retained in all builds.
- `AetherLog.event(tag, event, fields, level, throwable)` for structured events.
- `AetherLog.recentLogsForExport()` for diagnostic export snapshots.

## Event naming

Use lowercase snake case and name events by domain, action, and outcome.

Recommended pattern:

```text
<domain>_<operation>_<outcome>
```

Examples:

- `llm_chat_completion_start`
- `llm_chat_completion_success`
- `llm_stream_completion_failed`
- `agent_stream_reconnect_scheduled`
- `provider_model_fetch_success`
- `session_title_generation_empty`
- `workspace_upload_verify_timeout`
- `attachment_import_failed`

## Safe fields

Structured fields must use a positive allowlist. Prefer stable metadata that helps diagnose failures without exposing content.

Allowed field types:

- Provider and model identifiers.
- Host summaries such as `base_host`.
- Counts such as `conversation_count`, `tool_count`, `attachment_count`, and `model_count`.
- Status metadata such as `http_status`, `error_type`, and `retry_after_ms`.
- Timing metadata such as `elapsed_ms`, `duration_ms`, and `delay_ms`.
- Boolean states such as `stream`, `received_text`, and `parallel_tool_calls`.
- Redacted URI summaries from `AetherLog.summarizeUri()`.
- Redacted path summaries from `AetherLog.summarizePath()`.

## Forbidden fields

Never log these values directly:

- API keys, tokens, bearer headers, passwords, secrets, or refresh tokens.
- System prompts.
- User message text.
- Assistant response text.
- Tool output content unless it is already summarized and known safe.
- Request bodies.
- Response bodies or response previews.
- Full file paths.
- Full content or file URIs.
- Raw stack traces from LLM provider errors that may include response previews.

## Redaction behavior

`AetherLog` applies automatic sanitization before writing to logcat, storing recent in-memory logs, or exporting diagnostics.

Current sanitization covers:

- Sensitive assignments such as `api_key=...`, `token=...`, and `password=...`.
- Bearer tokens.
- `content://` and `file://` URIs.
- Android absolute paths under common app and storage roots.
- Long Base64-like values.
- Long logcat lines through `sanitizeForLogcat()` truncation.

Automatic sanitization is a safety net. Callers must still avoid passing sensitive content to logs.

## In-memory recent logs

`AetherLog` keeps a 300-entry in-memory ring buffer of sanitized recent application logs. Diagnostic exports include this buffer before system logcat output.

Export layout:

```text
recentAppLogs:
...

logcat:
...
```

This gives diagnostics useful app-level context even when logcat access fails or is truncated.

## Current structured event chains

- Attachment import: `attachment_import_*`
- Workspace upload: `workspace_upload_*`
- Provider model fetch: `provider_model_fetch_*`
- Session title generation: `session_title_generation_*`
- LLM non-streaming requests: `llm_chat_completion_*`
- LLM streaming requests: `llm_stream_completion_*`
- Agent stream reconnects: `agent_stream_reconnect_*`

## Validation commands

Run these before handing off logging changes:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:compileDebugKotlin
.\gradlew.bat :app:installDebug
```

If no Android device is connected, `installDebug` may fail with `No connected devices!`. Treat that as an environment limitation after compilation and unit tests pass.
