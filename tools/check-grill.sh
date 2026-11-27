#!/usr/bin/env bash
# Prüft, dass der Regelblock in allen vier Markdown-Dateien wortgleich ist
# und dass die beiden SKILL.md gleich sind. Aufruf: check-grill.sh [<kursrepo-oder-client-ordner>]
set -u
dir="${1:-.}"
files=(
  ".agents/skills/grill-me/SKILL.md"
  ".claude/skills/grill-me/SKILL.md"
  ".claude/rules/grill-regeln.md"
  "AGENTS.md"
)
failed=0
blocks=$(mktemp -d)
trap 'rm -rf "$blocks"' EXIT

for file in "${files[@]}"; do
  if [ ! -f "$dir/$file" ]; then
    echo "FEHLT: $file"; failed=1; continue
  fi
  starts=$(tr -d '\r' < "$dir/$file" | grep -c '^<!-- grill-regeln:start -->$')
  ends=$(tr -d '\r' < "$dir/$file" | grep -c '^<!-- grill-regeln:end -->$')
  if [ "$starts" != 1 ] || [ "$ends" != 1 ]; then
    echo "MARKER FEHLEN oder doppelt: $file"; failed=1; continue
  fi
  tr -d '\r' < "$dir/$file" | sed -n '/^<!-- grill-regeln:start -->$/,/^<!-- grill-regeln:end -->$/p' > "$blocks/$(echo "$file" | tr '/' '_')"
done

reference=""
for file in "${files[@]}"; do
  copy="$blocks/$(echo "$file" | tr '/' '_')"
  [ -f "$copy" ] || continue
  if [ -z "$reference" ]; then reference="$copy"; referenceName="$file"; continue; fi
  if ! cmp -s "$reference" "$copy"; then
    echo "REGELBLOCK ABWEICHEND: $file weicht von $referenceName ab"; failed=1
  fi
done

if [ -f "$dir/${files[0]}" ] && [ -f "$dir/${files[1]}" ]; then
  if ! cmp -s <(tr -d '\r' < "$dir/${files[0]}") <(tr -d '\r' < "$dir/${files[1]}"); then
    echo "SKILL.md ABWEICHEND: ${files[0]} und ${files[1]} sind verschieden"; failed=1
  fi
fi

if [ "$failed" = 0 ]; then echo "OK"; fi
exit "$failed"
