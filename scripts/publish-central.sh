#!/usr/bin/env bash
# Uploads an assembled deployment to Maven Central through the Portal API.
#
# Central takes a whole deployment as ONE zip in Maven repository layout, so
# both modules go up together or neither does. The old OSSRH staging API this
# replaces was retired in June 2025; there is no `gradle publish` straight to a
# remote any more.
#
# Needs, in the environment:
#   CENTRAL_USERNAME / CENTRAL_PASSWORD  a Portal token pair, NOT the account
#                                        password. central.sonatype.com,
#                                        Account, Generate User Token.
# Optional:
#   PUBLISHING_TYPE  USER_MANUAL (default) leaves the deployment VALIDATED and
#                    waiting for a human to press publish in the Portal.
#                    AUTOMATIC releases it the moment validation passes, which
#                    cannot be undone: a version on Central is permanent.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
STAGING="$ROOT/build/staging-deploy"
PUBLISHING_TYPE="${PUBLISHING_TYPE:-USER_MANAGED}"

: "${CENTRAL_USERNAME:?set CENTRAL_USERNAME to a Portal token username}"
: "${CENTRAL_PASSWORD:?set CENTRAL_PASSWORD to a Portal token password}"

[ -d "$STAGING" ] || { echo "no deployment at $STAGING - run gradle publish first" >&2; exit 1; }

# Every artifact Central will accept has to be signed. Checking here rather
# than letting the Portal reject the upload keeps the failure next to the
# cause, and catches the case where the signing key was absent and the build
# quietly produced an unsigned deployment instead of failing.
unsigned=$(find "$STAGING" \( -name '*.jar' -o -name '*.pom' -o -name '*.module' \) \
  -exec sh -c '[ -f "$1.asc" ] || echo "$1"' _ {} \;)
if [ -n "$unsigned" ]; then
  echo "refusing to upload: no PGP signature for" >&2
  echo "$unsigned" | sed 's#^#  #' >&2
  echo "MAVEN_GPG_PRIVATE_KEY was probably not set when gradle publish ran." >&2
  exit 1
fi

# Read from the tree being uploaded rather than asked of Gradle, so the name
# on the deployment cannot disagree with the artifacts inside it.
VERSION=$(basename "$(find "$STAGING" -type d -path '*/lnurlcash-kotlin/*' | head -1)")
BUNDLE="$ROOT/build/central-bundle.zip"
rm -f "$BUNDLE"

# maven-metadata is a repository-level index, not part of a deployment; Central
# builds its own. The pattern needs its leading wildcard: zip matches -x
# against the whole entry name, so a bare `maven-metadata*` matches nothing
# below the top directory and silently ships all of them.
(cd "$STAGING" && zip -q -r -X "$BUNDLE" . -x '*maven-metadata*')
echo "bundle: $BUNDLE ($(du -h "$BUNDLE" | cut -f1))"

TOKEN=$(printf '%s:%s' "$CENTRAL_USERNAME" "$CENTRAL_PASSWORD" | base64 | tr -d '\n')

response=$(curl --fail-with-body --silent --show-error \
  --request POST \
  --header "Authorization: Bearer $TOKEN" \
  --form "bundle=@$BUNDLE" \
  "https://central.sonatype.com/api/v1/publisher/upload?name=lnurlcash-kotlin-$VERSION&publishingType=$PUBLISHING_TYPE")

DEPLOYMENT_ID="$response"
echo "deployment: $DEPLOYMENT_ID"

# The upload returns as soon as the bundle is accepted, long before Central has
# checked it. Polling here is the difference between "the workflow went green"
# and "the release is actually valid".
for _ in $(seq 1 60); do
  sleep 10
  status=$(curl --fail-with-body --silent --show-error \
    --request POST \
    --header "Authorization: Bearer $TOKEN" \
    "https://central.sonatype.com/api/v1/publisher/status?id=$DEPLOYMENT_ID" \
    | tr -d ' \n')
  case "$status" in
    *'"deploymentState":"PENDING"'*|*'"deploymentState":"VALIDATING"'*)
      echo "validating..." ;;
    *'"deploymentState":"VALIDATED"'*)
      echo
      echo "VALIDATED and held. Nothing is on Central yet."
      echo "Release it at https://central.sonatype.com/publishing/deployments"
      exit 0 ;;
    *'"deploymentState":"PUBLISHING"'*)
      echo "publishing..." ;;
    *'"deploymentState":"PUBLISHED"'*)
      echo
      echo "PUBLISHED. It reaches search.maven.org within a few hours."
      exit 0 ;;
    *'"deploymentState":"FAILED"'*)
      echo
      echo "FAILED validation:" >&2
      echo "$status" >&2
      exit 1 ;;
    *)
      echo "unrecognised status: $status" >&2 ;;
  esac
done

echo "gave up waiting. Check https://central.sonatype.com/publishing/deployments" >&2
exit 1
