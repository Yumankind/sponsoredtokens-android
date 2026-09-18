#!/usr/bin/env bash
# Push this package's own history to its public mirror, github.com/Yumankind/sponsoredtokens-android.
#
# The library is developed inside the sponsoredtokens monorepo, next to the worker that verifies its
# signatures and the docs that are its contract; the mirror is the public face of the same code, and
# it is what JitPack builds. `git subtree split` rewrites this package's commits with the package
# directory as the root, deterministically - the same history produces the same commit ids - so every
# push is a fast-forward of the last one and no merge is ever needed at the far end.
#
# This is the CLI's own script (packages/sponsoredtokens-cli/scripts/mirror.sh) with the prefix and
# the remote changed, deliberately: two mirrors that work differently are two things to remember.
#
#   scripts/mirror.sh                      push to the public repository
#   MIRROR_DRY_RUN=1 scripts/mirror.sh     print the commit that would be pushed, push nothing
#   MIRROR_REMOTE=… MIRROR_BRANCH=… …      somewhere else
#
# A RELEASE IS A TAG ON THE MIRROR, not a file in the bucket: JitPack builds whatever tag an app
# asks for, so after pushing, tag the mirror (`git tag 0.1.0 <the split commit>` pushed to the
# remote) and that becomes the version a consumer writes in their dependency line.
set -euo pipefail
cd "$(dirname "$0")/../../.."
PREFIX=packages/sponsoredtokens-android
REMOTE="${MIRROR_REMOTE:-https://github.com/Yumankind/sponsoredtokens-android.git}"
BRANCH="${MIRROR_BRANCH:-main}"

if [ -n "$(git status --porcelain -- "$PREFIX")" ]; then
  echo "mirror: $PREFIX has uncommitted changes; commit them first" >&2
  exit 1
fi

SPLIT="$(git subtree split --prefix="$PREFIX" HEAD 2>/dev/null)"
echo "mirror: $SPLIT -> $REMOTE $BRANCH"

if [ "${MIRROR_DRY_RUN:-0}" = 1 ]; then
  echo "dry run - nothing pushed"
  exit 0
fi

git push "$REMOTE" "$SPLIT:refs/heads/$BRANCH"
echo "mirror: pushed. Tag the release on the mirror for JitPack to build:"
echo "  git tag <version> $SPLIT && git push $REMOTE <version>"
