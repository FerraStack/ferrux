#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Publishes ./screenshots/*.png to the orphan branch `ci-screenshots` under
# pr-<number>/<run_id>/ and creates or updates one PR comment embedding them.
# Needs: GH_TOKEN (contents: write, pull-requests: write), GITHUB_REPOSITORY.
set -euo pipefail

PR="${1:?usage: ci-post-screenshots.sh <pr-number> <run-id>}"
RUN="${2:?usage: ci-post-screenshots.sh <pr-number> <run-id>}"
REPO="${GITHUB_REPOSITORY:?}"
SRC="${SCREENSHOT_DIR:-screenshots}"
BRANCH=ci-screenshots
MARKER='<!-- ferra-screenshots -->'
DEST="pr-$PR/$RUN"

shopt -s nullglob
pngs=("$SRC"/*.png)
if [ ${#pngs[@]} -eq 0 ]; then
  echo "no screenshots to publish"
  exit 0
fi

work="$(mktemp -d)"
git -C "$work" init -q
git -C "$work" config user.name "github-actions[bot]"
git -C "$work" config user.email "41898282+github-actions[bot]@users.noreply.github.com"
git -C "$work" remote add origin "https://x-access-token:${GH_TOKEN}@github.com/${REPO}.git"

# Retry in case another run pushed to the branch concurrently.
for attempt in 1 2 3 4 5; do
  if git -C "$work" fetch -q --depth 1 origin "$BRANCH" 2>/dev/null; then
    git -C "$work" checkout -q -B "$BRANCH" FETCH_HEAD
  else
    git -C "$work" checkout -q --orphan "$BRANCH"
  fi
  mkdir -p "$work/$DEST"
  cp "${pngs[@]}" "$work/$DEST/"
  git -C "$work" add "$DEST"
  git -C "$work" commit -qm "screenshots: PR #$PR run $RUN"
  if git -C "$work" push -q origin "$BRANCH"; then
    break
  fi
  echo "push failed (attempt $attempt), retrying"
  git -C "$work" reset -q --hard
  sleep $((attempt * 2))
  [ "$attempt" -eq 5 ] && exit 1
done

base="https://raw.githubusercontent.com/$REPO/$BRANCH/$DEST"
run_url="https://github.com/$REPO/actions/runs/$RUN"
header="|"; sep="|"; cells="|"
for png in "${pngs[@]}"; do
  name="$(basename "$png")"
  header+=" ${name%.png} |"
  sep+=":---:|"
  cells+=" <img src=\"$base/$name\" width=\"220\"> |"
done
body="$MARKER
### Emulator screenshots
API 35 emulator, commit \`${GITHUB_SHA:0:7}\`, [run $RUN]($run_url).

$header
$sep
$cells"

comment_id="$(gh api --paginate "repos/$REPO/issues/$PR/comments" \
  --jq ".[] | select(.body | contains(\"$MARKER\")) | .id" | head -n1)"
if [ -n "$comment_id" ]; then
  gh api -X PATCH "repos/$REPO/issues/comments/$comment_id" -f body="$body" > /dev/null
  echo "updated comment $comment_id"
else
  gh api "repos/$REPO/issues/$PR/comments" -f body="$body" > /dev/null
  echo "created comment"
fi
