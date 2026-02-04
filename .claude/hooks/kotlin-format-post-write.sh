#!/bin/bash

# Read the tool input from stdin
input=$(cat)

# Extract the file path from the JSON input
file_path=$(echo "$input" | python3 -c "
import json, sys
try:
    data = json.load(sys.stdin)
    tool_input = data.get('tool_input', {})
    if isinstance(tool_input, dict):
        print(tool_input.get('file_path', ''))
    else:
        print(tool_input)
except:
    print('')
" 2>/dev/null)

# Only process .kt files
if [[ "$file_path" != *.kt ]]; then
    exit 0
fi

# Change to project directory
cd "$CLAUDE_PROJECT_DIR" || exit 0

# Run ktlintFormat using gradle
./gradlew ktlintFormat --quiet 2>/dev/null || true

exit 0
