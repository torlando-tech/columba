#!/usr/bin/env bash
# PostToolUse hook: auto-format .kt files after Write/Edit

FILE_PATH=$(jq -r '.tool_input.file_path // empty' 2>/dev/null)

[[ "$FILE_PATH" != *.kt ]] && exit 0
[[ ! -f "$FILE_PATH" ]] && exit 0

ktlint --format "$FILE_PATH" 2>/dev/null
exit 0
