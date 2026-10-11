#!/usr/bin/env bash
# Sync this repository with upstream DiPlay.
#
# Upstream: https://github.com/shihabal3amri/DiPlay  (GPL-3.0)
# All source code originates from upstream; this script only pulls its changes in.
#
# Usage: bash scripts/sync-upstream.sh [--apply]
#   --apply   actually merge upstream/main into the current branch.
#             Without it, the script only fetches and reports what would change.
set -euo pipefail

UPSTREAM_URL="https://github.com/shihabal3amri/DiPlay.git"
UPSTREAM_BRANCH="main"

# Files this fork changes relative to upstream; conflicts normally land here.
OUR_FILES=(
  "common/src/main/java/com/shilapi/xcertplay/CarPlayHostActivity.kt"
  "common/src/main/java/com/shilapi/xcertplay/ClusterMapPresentation.kt"
  "shared/src/main/java/com/shilapi/xcertplay/airplay/CarPlayMediaButton.kt"
)

if ! git remote get-url upstream >/dev/null 2>&1; then
  echo "Adding upstream remote: $UPSTREAM_URL"
  git remote add upstream "$UPSTREAM_URL"
fi

echo "==> Fetching upstream/$UPSTREAM_BRANCH"
git fetch upstream "$UPSTREAM_BRANCH"

BASE=$(git merge-base HEAD "upstream/$UPSTREAM_BRANCH")
echo "==> Commits since fork point ($(git rev-parse --short "$BASE")):"
git log --oneline "$BASE".."upstream/$UPSTREAM_BRANCH" | head -40

echo "==> Files changed upstream vs your HEAD:"
git diff --stat HEAD "upstream/$UPSTREAM_BRANCH" -- . | tail -30

echo "==> Overlap with the three files this fork modifies:"
for f in "${OUR_FILES[@]}"; do
  if [ -n "$(git diff --name-only HEAD "upstream/$UPSTREAM_BRANCH" -- "$f")" ]; then
    echo "  [conflict likely] $f"
  else
    echo "  [clean]           $f"
  fi
done

if [ "${1:-}" = "--apply" ]; then
  echo "==> Merging upstream/$UPSTREAM_BRANCH"
  git merge "upstream/$UPSTREAM_BRANCH" || {
    echo
    echo "Conflicts detected. Resolve them, then 'git commit'."
    echo "Re-apply the Leapmotor hunks using the semantic anchors in docs/LEAPMOTOR.md"
    echo "(do not rely on line numbers — upstream editing moves them)."
    exit 1
  }
  echo "==> Merged. Build and re-verify per README section 3."
else
  echo
  echo "Dry run only. To actually merge, re-run with: bash scripts/sync-upstream.sh --apply"
fi
