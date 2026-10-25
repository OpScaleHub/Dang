#!/usr/bin/env bash
# Cut a release of Dang (دنگ) entirely from this machine:
#   bump version -> run tests -> build signed APK -> update the landing page's
#   release.json -> commit + tag -> push -> GitHub Release with the APK attached.
# The landing page (site/) is deployed by .github/workflows/pages.yml on push.
#
# Usage: scripts/release.sh [patch|minor|major|X.Y.Z] [--dry-run] [--skip-tests]
#   default bump: patch.  --dry-run builds and shows everything but changes nothing.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO=OpScaleHub/Dang
BUMP=patch DRY=0 TESTS=1
for a in "$@"; do
  case "$a" in
    --dry-run) DRY=1 ;;
    --skip-tests) TESTS=0 ;;
    patch|minor|major) BUMP=$a ;;
    [0-9]*.[0-9]*.[0-9]*) BUMP=$a ;;
    -h|--help) sed -n 2,9p "$0"; exit 0 ;;
    *) echo "unknown argument: $a" >&2; exit 2 ;;
  esac
done

say() { printf '\n\033[1;33m==> %s\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

# ---- preflight --------------------------------------------------------------
command -v gh >/dev/null || die "gh (GitHub CLI) is required"
command -v python3 >/dev/null || die "python3 is required"
[ "$(git rev-parse --abbrev-ref HEAD)" = main ] || die "release from main"
[ -z "$(git status --porcelain)" ] || die "working tree not clean; commit or stash first"
if [ $DRY = 0 ]; then
  gh auth status >/dev/null 2>&1 || die "run: gh auth login"
  git fetch -q origin main
  [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || die "main is not in sync with origin/main"
fi

# ---- signing key (created once, kept outside the repo) ----------------------
SIGNING=${DONG_SIGNING:-$HOME/.config/dong/signing.properties}
if [ ! -f "$SIGNING" ]; then
  [ $DRY = 1 ] && die "no signing key at $SIGNING (run once without --dry-run to create it)"
  say "Creating release signing key (one time) in $(dirname "$SIGNING")"
  command -v keytool >/dev/null || die "keytool (JDK) is required"
  mkdir -p "$(dirname "$SIGNING")"; chmod 700 "$(dirname "$SIGNING")"
  PASS=$(python3 -c 'import secrets;print(secrets.token_urlsafe(24))')
  KS="$(dirname "$SIGNING")/dong-release.jks"
  keytool -genkeypair -keystore "$KS" -alias dong -keyalg RSA -keysize 4096 -validity 36500 \
    -storepass "$PASS" -keypass "$PASS" -dname "CN=Dang, O=OpScaleHub" >/dev/null 2>&1
  umask 077; printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=dong\nkeyPassword=%s\n' "$KS" "$PASS" "$PASS" > "$SIGNING"
  printf '\033[1;31m!! Back up %s and %s somewhere safe.\n!! Without them no future version can update installed copies.\033[0m\n' "$KS" "$SIGNING"
fi

# ---- version ----------------------------------------------------------------
cur=$(sed -n 's/^VERSION_NAME=//p' version.properties)
code=$(sed -n 's/^VERSION_CODE=//p' version.properties)
IFS=. read -r MA MI PA <<<"$cur"
case "$BUMP" in
  major) next="$((MA+1)).0.0" ;;
  minor) next="$MA.$((MI+1)).0" ;;
  patch) next="$MA.$MI.$((PA+1))" ;;
  *) next=$BUMP ;;
esac
next_code=$((code+1))
TAG="v$next"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
say "Releasing $cur -> $next (versionCode $next_code)"

restore() { git checkout -q -- version.properties site/release.json 2>/dev/null || true; }
[ $DRY = 1 ] && trap restore EXIT
sed -i "s/^VERSION_NAME=.*/VERSION_NAME=$next/; s/^VERSION_CODE=.*/VERSION_CODE=$next_code/" version.properties

# ---- test + build -----------------------------------------------------------
if [ $TESTS = 1 ]; then say "Running unit tests"; ./gradlew -q --console=plain testDebugUnitTest; fi
say "Building signed release APK"
./gradlew -q --console=plain assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
[ -f "$APK" ] || die "release APK missing (is the signing config valid?)"
OUT=build/release; mkdir -p "$OUT"
cp "$APK" "$OUT/dang.apk"; cp "$APK" "$OUT/dang-$next.apk"
say "Building signed Cafe Bazaar APK (no self-updater)"
./gradlew -q --console=plain assembleRelease -Pstore=bazaar
cp "$APK" "$OUT/dang-bazaar-$next.apk"
SHA=$(sha256sum "$OUT/dang.apk" | cut -d' ' -f1)
SIZE=$(stat -c %s "$OUT/dang.apk")
APKSIGNER=$(ls -d "${ANDROID_HOME:-$HOME/Android/Sdk}"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then "$APKSIGNER" verify --print-certs "$OUT/dang.apk" | grep -m1 "certificate DN"; else echo "(apksigner not found; skipped signature check)"; fi

# ---- landing-page metadata + notes ------------------------------------------
MIN_SDK=$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' app/build.gradle.kts | head -1)
python3 - "$next" "$SIZE" "$SHA" "$next_code" "$MIN_SDK" <<'PY'
import json, sys, datetime
v, size, sha, code, min_sdk = sys.argv[1], int(sys.argv[2]), sys.argv[3], int(sys.argv[4]), int(sys.argv[5])
d = datetime.date.today()
def jalali(g):
    gy, gm, gd = g.year, g.month, g.day
    gdm = [0,31,59,90,120,151,181,212,243,273,304,334]
    gy2 = gy + 1 if gm > 2 else gy
    days = 355666 + 365*gy + (gy2+3)//4 - (gy2+99)//100 + (gy2+399)//400 + gd + gdm[gm-1]
    jy = -1595 + 33*(days//12053); days %= 12053
    jy += 4*(days//1461); days %= 1461
    if days > 365: jy += (days-1)//365; days = (days-1) % 365
    jm = 1 + days//31 if days < 186 else 7 + (days-186)//30
    jd = 1 + (days % 31 if days < 186 else (days-186) % 30)
    return f"{jy}/{jm:02d}/{jd:02d}"
json.dump({
  "version": v, "versionCode": code, "minSdk": min_sdk,
  "date": d.isoformat(), "dateJalali": jalali(d),
  "sizeBytes": size, "sha256": sha,
  "apk": "https://github.com/OpScaleHub/Dang/releases/latest/download/dang.apk",
  # Read by the in-app updater (update/Updater.kt): fixed to this version's file.
  "apkPinned": f"https://github.com/OpScaleHub/Dang/releases/download/v{v}/dang-{v}.apk",
  "notes": f"https://github.com/OpScaleHub/Dang/releases/tag/v{v}",
}, open("site/release.json", "w"), indent=2, ensure_ascii=False)
open("site/release.json", "a").write("\n")
PY

PREV=$(git describe --tags --abbrev=0 2>/dev/null || true)
RANGE=${PREV:+$PREV..}HEAD
{
  echo "## دنگ $next"
  echo
  echo "**دریافت:** \`dang.apk\` (اندروید ۸ و بالاتر)"
  echo
  section() { local t=$1 p=$2 l; l=$(git log --no-merges --format='- %s' "$RANGE" | grep -E "^- $p(\(|:)" | sed -E "s/^- $p(\([^)]*\))?: /- /" || true); if [ -n "$l" ]; then printf '### %s\n%s\n\n' "$t" "$l"; fi; }
  section "Features" feat
  section "Fixes" fix
  section "Performance" perf
  other=$(git log --no-merges --format='- %s' "$RANGE" | grep -vE '^- (feat|fix|perf|chore\(release\))(\(|:)' || true)
  if [ -n "$other" ]; then printf '### Other\n%s\n\n' "$other"; fi
  echo "SHA-256 (\`dang.apk\`): \`$SHA\`"
} > "$OUT/notes.md"

say "Release notes"; cat "$OUT/notes.md"
if [ $DRY = 1 ]; then say "Dry run: built $OUT/dang.apk ($SIZE bytes); nothing committed or pushed"; exit 0; fi

# ---- commit, tag, push, publish ---------------------------------------------
say "Committing, tagging and pushing $TAG"
git add version.properties site/release.json
git commit -q -m "chore(release): $TAG"
git tag -a "$TAG" -m "Dang $next"
git push -q origin main "$TAG"

say "Publishing GitHub Release"
gh release create "$TAG" "$OUT/dang.apk" "$OUT/dang-$next.apk" "$OUT/dang-bazaar-$next.apk" \
  --repo "$REPO" --title "دنگ $next" --notes-file "$OUT/notes.md" --latest

say "Done: https://github.com/$REPO/releases/tag/$TAG"
echo "Landing page updates via GitHub Pages in a minute: https://opscalehub.github.io/Dang/"
