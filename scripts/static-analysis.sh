#!/usr/bin/env bash
# Report-only static analysis: detekt (Kotlin) and the Dependency Analysis Gradle Plugin
# (unused / misdeclared dependencies). Run by .github/workflows/static-analysis.yml.
#
# The two plugins are added to the root build file only for the length of this run, then the
# file is put back: the normal build never resolves them, so an alpha detekt or a plugin's own
# Kotlin/AGP requirements can't affect it. Reports land in:
#   app/build/reports/detekt/             detekt findings
#   build/reports/dependency-analysis/    build-health-report.txt
#
#   scripts/static-analysis.sh
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DETEKT_VERSION=2.0.0-alpha.6   # 2.x: the first line that reads Kotlin 2.x sources
DAGP_VERSION=3.9.0

cp build.gradle.kts build.gradle.kts.static-analysis-backup
trap 'mv -f build.gradle.kts.static-analysis-backup build.gradle.kts' EXIT

python3 - "$DETEKT_VERSION" "$DAGP_VERSION" <<'PY'
import sys, pathlib
detekt, dagp = sys.argv[1], sys.argv[2]
p = pathlib.Path("build.gradle.kts")
s = p.read_text()
anchor = 'id("com.android.library") version'
i = s.index(anchor)
j = s.index("\n", i) + 1
s = s[:j] + f'    id("com.autonomousapps.dependency-analysis") version "{dagp}"\n' \
          + f'    id("dev.detekt") version "{detekt}" apply false\n' + s[j:]
s += '''
// Added by scripts/static-analysis.sh for its run only.
subprojects {
    pluginManager.withPlugin("com.android.application") { apply(plugin = "dev.detekt") }
}
'''
p.write_text(s)
PY

status=0
./gradlew --no-configuration-cache --continue buildHealth :app:detekt || status=$?

echo
for report in build/reports/dependency-analysis/build-health-report.txt; do
    [ -f "$report" ] && { echo "== $report"; cat "$report"; }
done
ls app/build/reports/detekt 2>/dev/null && echo "detekt reports: app/build/reports/detekt/"
# Findings are reported, not enforced (yet): exit non-zero only when the tools themselves failed
# to run, which the workflow surfaces as a warning.
exit $status
