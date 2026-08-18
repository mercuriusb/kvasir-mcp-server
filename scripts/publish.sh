#!/usr/bin/env bash
#
# Publishes one state of this repository to a branch that carries no history of its own.
#
#     scripts/publish.sh              # veroeffentlicht HEAD
#     scripts/publish.sh v1.2.3       # veroeffentlicht genau dieses Tag
#
# WARUM UEBERHAUPT
#
# Ein "git push github main" wuerde die vollstaendige Historie mitnehmen. Darin stehen aeltere
# Faßungen mit internen Adressen, und in jedem einzelnen Commit die private Mailadresse des
# Autors. Beides laesst sich nachtraeglich nur durch Umschreiben der Historie entfernen - was
# unumkehrbar ist und alles neu erzwingt, was schon gepusht wurde.
#
# Dieser Weg umgeht das: der Zweig "publish" enthaelt pro veroeffentlichtem Stand genau einen
# Commit, dessen Baum mit dem der Quelle uebereinstimmt. Was nie in diesen Zweig kommt, kann auch
# nicht bei GitHub landen.
#
# WIE
#
# Ueber git commit-tree, nicht ueber checkout. Der Baum der Quelle wird unveraendert
# uebernommen und ein Commit darauf gesetzt; das Arbeitsverzeichnis wird dabei nie angefasst.
# Kein Wechsel des Zweiges, keine halbfertigen Zustaende, kein Risiko fuer lokale Dateien.
#
# IDENTITAET
#
# Autor und Committer lassen sich ueber PUBLISH_NAME und PUBLISH_EMAIL setzen. Ohne das gilt die
# uebliche Git-Konfiguration - dann steht die dortige Adresse im oeffentlichen Zweig.
#
#     PUBLISH_EMAIL=12345+user@users.noreply.github.com scripts/publish.sh v1.2.3
set -euo pipefail

cd "$(dirname "$0")/.."

BRANCH="${PUBLISH_BRANCH:-publish}"
REMOTE="${PUBLISH_REMOTE:-github}"
SOURCE="${1:-HEAD}"

# Der letzte Schritt bleibt bewusst von Hand: dieses Skript baut den Stand, das Veroeffentlichen
# entscheidet der Mensch. Der Hinweis erscheint auch dann, wenn der Baum schon einen Commit hat -
# gebaut heisst nicht gepusht, und genau diese beiden Zustaende verwechselt man sonst.
push_hint() {
    echo
    echo "Noch zu tun - veroeffentlichen mit:"
    echo
    echo "    git push $REMOTE $BRANCH:main"
    if ! git remote get-url "$REMOTE" >/dev/null 2>&1; then
        echo
        echo "Die Gegenstelle $REMOTE fehlt noch:"
        echo "    git remote add $REMOTE <url>"
    fi
}

git rev-parse -q --verify "$SOURCE^{commit}" >/dev/null \
    || { echo "Unbekannte Quelle: $SOURCE" >&2; exit 1; }

TREE=$(git rev-parse "$SOURCE^{tree}")

# Der Waechter. Der ganze Sinn dieses Zweiges ist, dass nur Geprueftes hinausgeht - also wird der
# Baum vor dem Commit durchsucht und nicht darauf vertraut, dass daran gedacht wurde.
#
# Was hier NICHT steht, ist Absicht: keine Zugangsdaten, keine internen Hostnamen. Diese Datei
# wird selbst mit veroeffentlicht - stuenden die verbotenen Werte hier, waere der Waechter genau
# das Leck, das er verhindern soll, und er wuerde ausserdem bei jedem Lauf sich selbst finden.
FOUND=0

# Allgemeingueltige Muster, die nirgendwo in Quelltext gehoeren.
GENERIC=(
    "AKIA[0-9A-Z]{16}"
    "BEGIN [A-Z ]*PRIVATE KEY"
    "ghp_[A-Za-z0-9]{30,}"
)
for pattern in "${GENERIC[@]}"; do
    if hits=$(git grep -I -E -n "$pattern" "$SOURCE" 2>/dev/null); then
        echo "Nicht veroeffentlichungsfaehig - Treffer fuer /$pattern/:" >&2
        echo "$hits" | head -5 | sed 's/^/  /' >&2
        FOUND=1
    fi
done

# Ortsspezifisches kommt aus zwei nicht versionierten Quellen, damit es das Repository nie
# erreicht:
#
#   .env          jeder Wert ab 16 Zeichen wird woertlich gesucht. Das deckt Zugangsdaten und
#                 vollstaendige Adressen ab, ohne sie hier zu nennen. Kurze Werte wie eine
#                 AWS-Region bleiben aussen vor - die stehen zu Recht in der Konfiguration.
#   .publish-deny eine Regel je Zeile, fuer alles Weitere: Domains, Netze, Kundennamen.
LITERALS=()
if [[ -f .env ]]; then
    while IFS= read -r value; do
        [[ ${#value} -ge 16 ]] && LITERALS+=("$value")
    done < <(sed -E 's/^[[:space:]]*#.*//; s/^[A-Za-z_][A-Za-z0-9_]*=//; s/^["'"'"']//; s/["'"'"']$//' .env | grep -v '^$')
fi
for literal in ${LITERALS+"${LITERALS[@]}"}; do
    if hits=$(git grep -I -F -n "$literal" "$SOURCE" 2>/dev/null); then
        echo "Nicht veroeffentlichungsfaehig - ein Wert aus .env steht im Baum:" >&2
        echo "$hits" | head -3 | sed 's/^/  /' >&2
        FOUND=1
    fi
done

DENY_FILE="${PUBLISH_DENY_FILE:-.publish-deny}"
if [[ -f "$DENY_FILE" ]]; then
    while IFS= read -r pattern; do
        [[ -z "$pattern" || "$pattern" == \#* ]] && continue
        if hits=$(git grep -I -E -n "$pattern" "$SOURCE" 2>/dev/null); then
            echo "Nicht veroeffentlichungsfaehig - $DENY_FILE verbietet /$pattern/:" >&2
            echo "$hits" | head -5 | sed 's/^/  /' >&2
            FOUND=1
        fi
    done < "$DENY_FILE"
fi

# Fremde Archive und Binaerdateien: nichts, was dieses Repository mitverteilen sollte.
if jars=$(git ls-tree -r --name-only "$SOURCE" | grep -E '\.(jar|onnx|p12|jks|pem|key)$'); then
    echo "Nicht veroeffentlichungsfaehig - Binaerdateien im Baum:" >&2
    echo "$jars" | sed 's/^/  /' >&2
    FOUND=1
fi

[[ $FOUND -eq 0 ]] || { echo >&2; echo "Nichts veroeffentlicht." >&2; exit 1; }

# Ein sprechender Name fuer den Stand: das Tag, wenn es eines ist, sonst die Kurzfassung.
DESCRIPTION=$(git describe --tags --exact-match "$SOURCE" 2>/dev/null || git rev-parse --short "$SOURCE")
MESSAGE="${PUBLISH_MESSAGE:-Kvasir $DESCRIPTION}"

PARENT=()
if git rev-parse -q --verify "refs/heads/$BRANCH" >/dev/null; then
    PREVIOUS=$(git rev-parse "refs/heads/$BRANCH")
    if [[ "$(git rev-parse "$PREVIOUS^{tree}")" == "$TREE" ]]; then
        echo "Der Baum von $SOURCE liegt bereits als $(git rev-parse --short "$PREVIOUS") im Zweig $BRANCH."
        push_hint
        exit 0
    fi
    PARENT=(-p "$PREVIOUS")
fi

COMMIT=$(
    GIT_AUTHOR_NAME="${PUBLISH_NAME:-$(git config user.name)}" \
    GIT_AUTHOR_EMAIL="${PUBLISH_EMAIL:-$(git config user.email)}" \
    GIT_COMMITTER_NAME="${PUBLISH_NAME:-$(git config user.name)}" \
    GIT_COMMITTER_EMAIL="${PUBLISH_EMAIL:-$(git config user.email)}" \
    git commit-tree "$TREE" "${PARENT[@]}" -m "$MESSAGE"
)
git update-ref "refs/heads/$BRANCH" "$COMMIT"

echo "Geprueft und gebaut: Zweig $BRANCH steht auf $(git rev-parse --short "$COMMIT") - \"$MESSAGE\""
echo "Commits in diesem Zweig: $(git rev-list --count "$BRANCH")"
push_hint
