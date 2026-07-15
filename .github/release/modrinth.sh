#!/usr/bin/env bash
# Upload a built jar as a new version on Modrinth.
# Usage: modrinth.sh <jar-path> <version-number>
# Requires: MODRINTH_TOKEN. Optional: MODRINTH_PROJECT_ID, MODRINTH_LOADERS,
#           GAME_VERSION_PREFIX. Reads release notes from RELEASE_NOTES.md.
set -euo pipefail

JAR="$1"
VERSION="$2"
API="https://api.modrinth.com/v2"
UA="joogiebear/plugin-release (joogiebear@protonmail.com)"

if [ -z "${MODRINTH_TOKEN:-}" ]; then
  echo "::error::MODRINTH_TOKEN secret is not set - cannot upload to Modrinth."
  exit 1
fi

ARTIFACT_ID=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.artifactId)
NAME=$(mvn -q -DforceStdout help:evaluate -Dexpression=project.name 2>/dev/null || echo "$ARTIFACT_ID")
SLUG="${MODRINTH_PROJECT_ID:-$ARTIFACT_ID}"

# Resolve the project's real id from its slug.
if ! PROJECT_JSON=$(curl -sf -H "Authorization: $MODRINTH_TOKEN" -H "User-Agent: $UA" "$API/project/$SLUG"); then
  echo "::error::Modrinth project '$SLUG' not found. Create it first, or set the"
  echo "::error::MODRINTH_PROJECT_ID repo variable to the correct slug/id."
  exit 1
fi
PID=$(echo "$PROJECT_JSON" | jq -r '.id')

# All released Minecraft versions matching the configured prefix (e.g. 1.21).
GV=$(curl -sf -H "User-Agent: $UA" "$API/tag/game_version" \
  | jq -c --arg p "${GAME_VERSION_PREFIX:-1.21}" \
      '[.[] | select(.version_type=="release") | select(.version|startswith($p)) | .version]')
if [ "$GV" = "[]" ] || [ -z "$GV" ]; then
  echo "::error::No Modrinth game versions matched prefix '${GAME_VERSION_PREFIX:-1.21}'."
  exit 1
fi

# Loaders: accept comma- or space-separated list -> JSON array.
LOADERS=$(echo "${MODRINTH_LOADERS:-paper}" | tr ', ' '\n' | grep -v '^$' | jq -R . | jq -cs .)

CHANGELOG=$(cat RELEASE_NOTES.md 2>/dev/null || echo "Release $VERSION")

DATA=$(jq -cn \
  --arg name "$NAME $VERSION" \
  --arg vn "$VERSION" \
  --arg cl "$CHANGELOG" \
  --arg pid "$PID" \
  --argjson gv "$GV" \
  --argjson loaders "$LOADERS" \
  '{name:$name, version_number:$vn, changelog:$cl, dependencies:[],
    game_versions:$gv, version_type:"release", loaders:$loaders,
    featured:true, status:"listed", project_id:$pid,
    file_parts:["file"], primary_file:"file"}')

echo "Uploading $JAR -> Modrinth project '$SLUG' ($PID), loaders $LOADERS"
HTTP=$(curl -s -o resp.json -w '%{http_code}' -X POST "$API/version" \
  -H "Authorization: $MODRINTH_TOKEN" \
  -H "User-Agent: $UA" \
  -F "data=$DATA;type=application/json" \
  -F "file=@$JAR;type=application/java-archive")

if [ "$HTTP" -ge 200 ] && [ "$HTTP" -lt 300 ]; then
  echo "Modrinth version created: $(jq -r '.id' resp.json)"
else
  echo "::error::Modrinth upload failed (HTTP $HTTP):"
  cat resp.json
  exit 1
fi
