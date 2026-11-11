#!/usr/bin/env bash
# Secure coding checks for Hubitat Groovy code. Each rule matches one requirement in SECURITY.md.
# Run from the repository root: bash .github/scripts/security-check.sh
# Exits non-zero when any rule fails, and prints the offending lines.
set -u

mapfile -t FILES < <(git ls-files '*.groovy')
failed=0

fail() {
    echo "FAIL [$1] $2"
    [ -n "${3:-}" ] && echo "$3" | sed 's/^/    /'
    failed=1
}

# grep_rule ID "description" "extended regex"
grep_rule() {
    local hits
    # A line may opt out of one rule with a reviewed, explained marker: // security-check: allow S1 (reason)
    hits=$(grep -nE "$3" "${FILES[@]}" 2>/dev/null | grep -vE "security-check: allow $1 \(.+\)" || true)
    [ -n "$hits" ] && fail "$1" "$2" "$hits"
}

if [ ${#FILES[@]} -eq 0 ]; then
    echo "No Groovy files found"
    exit 1
fi

# S1: secrets are never stored in state (State Variables are shown on the device page).
grep_rule S1 "secret stored in state; use a password-type preference" \
    "state\.(pk|apiKey|api_key|clientKey|client_key|token|accessToken|password|secret)[[:space:]]*="

# S2: secret values are never written to the logs.
grep_rule S2 "secret value interpolated into a log line" \
    "log[._a-zA-Z]*[[:space:](].*\\$\{?[[:space:]]*(apiKey|clientKey|state\.pk|password|accessToken|token)\b"

# S3: raw inbound messages are never logged (they can carry keys); log their shape instead.
grep_rule S3 "raw inbound message logged" \
    "log[._a-zA-Z]*[[:space:](].*\\$\{description\}"

# S4: no hard-coded API keys or authorization headers.
grep_rule S4 "hard-coded credential" \
    "(X-API-KEY|Authorization)['\"]?[[:space:]]*:[[:space:]]*['\"][A-Za-z0-9_\-]{8,}"

# S5: pages must not load remote resources (images, scripts, styles) when opened.
grep_rule S5 "remote resource loaded by a page" \
    "(url\(['\"]?https?://|<(img|script|link|iframe)[^>]*(src|href)=['\"]?https?://)"

# S6: links that open a new tab use rel="noopener noreferrer".
hits=$(grep -nE 'target="_blank"' "${FILES[@]}" | grep -v 'noopener' || true)
[ -n "$hits" ] && fail S6 'target="_blank" without rel="noopener noreferrer"' "$hits"

# S7: debug logging is off by default.
grep_rule S7 "debug logging on by default" \
    "(name:[[:space:]]*'logEnable'.*defaultValue:[[:space:]]*true|defaultValue:[[:space:]]*'1'[[:space:]]*$)"

# S7: debug logging switches itself off: any file with a debug logging setting must schedule logsOff.
for f in "${FILES[@]}"; do
    if grep -qE "(input[[:space:](]+|name:[[:space:]]*)'(logEnable|logLevel)'" "$f" && ! grep -q "logsOff" "$f"; then
        fail S7 "$f has a debug logging setting but never turns it off automatically (logsOff)"
    fi
done

# S8: every file that skips certificate checks says why, by pointing to SECURITY.md.
for f in "${FILES[@]}"; do
    if grep -q "ignoreSSLIssues:[[:space:]]*true" "$f" && ! grep -q "SECURITY.md" "$f"; then
        fail S8 "$f skips certificate checks without referencing SECURITY.md"
    fi
done

# S9: continuity records and secrets files are never committed.
hits=$(git ls-files | grep -iE '(Living_Continuity_Record|\.key$|\.pem$|(^|/)\.env($|\.)|(^|/)secrets/)' || true)
[ -n "$hits" ] && fail S9 "file that must stay local is tracked" "$hits"

if [ $failed -eq 0 ]; then
    echo "PASS all secure coding checks (${#FILES[@]} Groovy file(s))"
fi
exit $failed
