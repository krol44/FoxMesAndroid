#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROPERTIES="$ROOT/gradle.properties"
CHANGELOG="$ROOT/changelog.txt"

usage() {
  echo "Usage:"
  echo "  $(basename "$0") X.Y.Z     Bump the FoxMes Android version to X.Y.Z everywhere"
  echo "  $(basename "$0") --check   Verify all hardcoded version literals match gradle.properties"
  exit 1
}

[ $# -eq 1 ] || usage

current_version() {
  sed -n 's/^APP_VERSION_NAME=//p' "$PROPERTIES"
}

current_code() {
  sed -n 's/^APP_VERSION_CODE=//p' "$PROPERTIES"
}

code_for() {
  local major minor patch
  IFS='.' read -r major minor patch <<< "$1"
  echo $((10#$major * 1000000 + 10#$minor * 1000 + 10#$patch))
}

PATCH_FILES=()
PATCH_OLD=()
PATCH_NEW=()

add_patch() {
  PATCH_FILES+=("$1")
  PATCH_OLD+=("$2")
  PATCH_NEW+=("$3")
}

build_patch_list() {
  local ov="$1" nv="$2"
  PATCH_FILES=(); PATCH_OLD=(); PATCH_NEW=()

  add_patch "$ROOT/README.md" \
    "FoxMes Android $ov" "FoxMes Android $nv"
  add_patch "$ROOT/README.md" \
    "FoxMes-$ov-android.apk" "FoxMes-$nv-android.apk"

  add_patch "$ROOT/foxmes/release-notes.md" \
    "FoxMes Android $ov" "FoxMes Android $nv"
}

# RELEASE.md is a gitignored local checklist, so it may be missing or lag
# several versions behind - rewrite any version in it instead of matching $OLD.
sync_release_md() {
  local file="$ROOT/RELEASE.md" v="$1"
  [ -f "$file" ] || return 0
  NEW_VER="$v" perl -pi -e '
    s/(make version VERSION=)\d+\.\d+\.\d+/$1$ENV{NEW_VER}/g;
    s/(-m ")\d+\.\d+\.\d+(")/$1$ENV{NEW_VER}$2/g;
    s/(git tag v)\d+\.\d+\.\d+/$1$ENV{NEW_VER}/g;
    s/(git push origin v)\d+\.\d+\.\d+/$1$ENV{NEW_VER}/g;
  ' "$file"
  echo "  synced RELEASE.md to $v"
}

apply_patches() {
  local i file old new
  for i in "${!PATCH_FILES[@]}"; do
    file="${PATCH_FILES[$i]}"
    old="${PATCH_OLD[$i]}"
    new="${PATCH_NEW[$i]}"
    if ! grep -qF -- "$old" "$file"; then
      echo "ERROR: expected text not found in ${file#"$ROOT"/}:" >&2
      echo "  $old" >&2
      echo "The file changed since set-version.sh was written - update its patch list." >&2
      exit 1
    fi
    OLD_LIT="$old" NEW_LIT="$new" perl -pi -e 's/\Q$ENV{OLD_LIT}\E/$ENV{NEW_LIT}/g' "$file"
    echo "  patched ${file#"$ROOT"/}"
  done
}

check_patches() {
  local i file new failed=0
  for i in "${!PATCH_FILES[@]}"; do
    file="${PATCH_FILES[$i]}"
    new="${PATCH_NEW[$i]}"
    if grep -qF -- "$new" "$file"; then
      printf "  OK    %s\n" "${file#"$ROOT"/}"
    else
      printf "  FAIL  %s: expected %s\n" "${file#"$ROOT"/}" "$new"
      failed=1
    fi
  done
  return $failed
}

if [ "$1" = "--check" ]; then
  CUR="$(current_version)"
  CUR_CODE="$(current_code)"
  echo "Canonical version (gradle.properties): $CUR ($CUR_CODE)"
  echo
  failed=0
  if [ "$CUR_CODE" != "$(code_for "$CUR")" ]; then
    printf "  FAIL  gradle.properties: APP_VERSION_CODE should be %s for %s\n" "$(code_for "$CUR")" "$CUR"
    failed=1
  fi
  build_patch_list "$CUR" "$CUR"
  check_patches || failed=1
  if [ "$failed" = 0 ]; then
    echo
    echo "All FoxMes Android version literals match."
    exit 0
  else
    echo
    echo "Some files are out of sync with gradle.properties - fix them or re-run the bump." >&2
    exit 1
  fi
fi

NEW="$1"
[[ "$NEW" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || {
  echo "Version must look like X.Y.Z (e.g. 1.1.0), got: $NEW" >&2
  exit 1
}
NEW_CODE="$(code_for "$NEW")"

OLD="$(current_version)"
OLD_CODE="$(current_code)"

if [ "$NEW" = "$OLD" ]; then
  echo "Already at version $OLD, nothing to do."
  sync_release_md "$NEW"
  exit 0
fi

if ! grep -qE "^${NEW//./\\.} \(" "$CHANGELOG"; then
  echo "ERROR: changelog.txt has no entry for $NEW - add one starting with \"$NEW (DD.MM.YY)\" first." >&2
  exit 1
fi

echo "Bumping FoxMes Android version: $OLD ($OLD_CODE) -> $NEW ($NEW_CODE)"
echo

OLD_LIT="APP_VERSION_NAME=$OLD" NEW_LIT="APP_VERSION_NAME=$NEW" \
  perl -pi -e 's/^\Q$ENV{OLD_LIT}\E$/$ENV{NEW_LIT}/' "$PROPERTIES"
OLD_LIT="APP_VERSION_CODE=$OLD_CODE" NEW_LIT="APP_VERSION_CODE=$NEW_CODE" \
  perl -pi -e 's/^\Q$ENV{OLD_LIT}\E$/$ENV{NEW_LIT}/' "$PROPERTIES"
if [ "$(current_version)" != "$NEW" ] || [ "$(current_code)" != "$NEW_CODE" ]; then
  echo "ERROR: gradle.properties ended up at $(current_version) ($(current_code)), expected $NEW ($NEW_CODE)." >&2
  exit 1
fi
echo "  patched gradle.properties"

build_patch_list "$OLD" "$NEW"
apply_patches
sync_release_md "$NEW"
echo

echo "Verifying..."
build_patch_list "$NEW" "$NEW"
if check_patches; then
  echo
  echo "Version bumped: $OLD -> $NEW. Review the diff and commit."
else
  echo
  echo "ERROR: some files did not end up consistent - see above." >&2
  exit 1
fi
