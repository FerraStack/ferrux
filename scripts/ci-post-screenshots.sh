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
SHA="${HEAD_SHA:-${GITHUB_SHA:-unknown}}"
MARKER='<!-- ferra-screenshots -->'
DEST="pr-$PR/$RUN"
KEEP_RUNS="${KEEP_RUNS:-3}"   # older runs of this PR are pruned
PER_ROW=3

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
  # Keep only the newest $KEEP_RUNS runs (run ids increase monotonically).
  ls -1 "$work/pr-$PR" | sort -n | head -n -"$KEEP_RUNS" | while read -r old; do
    git -C "$work" rm -rq --ignore-unmatch "pr-$PR/$old" || rm -rf "${work:?}/pr-$PR/$old"
  done
  git -C "$work" add -A "pr-$PR"
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
status="${JOB_STATUS:-success}"
table=""
row_head="|"; row_img="|"; n=0
flush_row() {
  [ "$n" -eq 0 ] && return
  while [ "$n" -lt "$PER_ROW" ]; do row_head+="  |"; row_img+="  |"; n=$((n + 1)); done
  table+="$row_head"$'\n'
  [ -z "${sep_done:-}" ] && { table+="|$(printf ':---:|%.0s' $(seq 1 "$PER_ROW"))"$'\n'; sep_done=1; }
  table+="$row_img"$'\n'
  row_head="|"; row_img="|"; n=0
}
for png in "${pngs[@]}"; do
  name="$(basename "$png")"
  row_head+=" \`${name%.png}\` |"
  row_img+=" <a href=\"$base/$name\"><img src=\"$base/$name\" width=\"260\"></a> |"
  n=$((n + 1))
  [ "$n" -eq "$PER_ROW" ] && flush_row
done
flush_row

body="$MARKER
### Emulator screenshots
API 35 emulator (Pixel 6 profile), commit \`${SHA:0:7}\`, [run $RUN]($run_url), job status: **$status**.
Every screenshot step is checked against the terminal grid and UI tree; see the job log.

$table"

comment_id="$(gh api --paginate "repos/$REPO/issues/$PR/comments" \
  --jq ".[] | select(.body | contains(\"$MARKER\")) | .id" | head -n1)"
if [ -n "$comment_id" ]; then
  gh api -X PATCH "repos/$REPO/issues/comments/$comment_id" -f body="$body" > /dev/null
  echo "updated comment $comment_id"
else
  gh api "repos/$REPO/issues/$PR/comments" -f body="$body" > /dev/null
  echo "created comment"
fi
