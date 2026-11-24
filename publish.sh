#!/usr/bin/env bash
# Публикует собранный APK в GitHub Releases (для автообновления приложения).
#
# Использование:
#   ./publish.sh <tag> ["release notes"]
#
# Пример:
#   ./publish.sh v36 "Исправлены каналы, добавлен звук"
#
# Токен берётся из переменной GITHUB_TOKEN или из файла ~/.config/antontv/token
# (файл не должен попадать в публичный репозиторий).
set -euo pipefail

REPO="xSEAmon/xseamon-tv"
APK="${APK:-app/build/outputs/apk/release/AntonTV-3.0.5.apk}"

TAG="${1:-}"
NOTES="${2:-Новая версия}"

if [ -z "$TAG" ]; then
  echo "Укажите тег релиза (versionCode, например v36)." >&2
  echo "Текущий versionCode/versionName смотрите в app/build.gradle.kts." >&2
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "APK не найден: $APK" >&2
  exit 1
fi

TOKEN="${GITHUB_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$HOME/.config/antontv/token" ]; then
  TOKEN="$(tr -d '[:space:]' < "$HOME/.config/antontv/token")"
fi

if [ -z "$TOKEN" ]; then
  echo "Нет токена GitHub. Положите его в ~/.config/antontv/token или задайте GITHUB_TOKEN." >&2
  exit 1
fi

APK_NAME="$(basename "$APK")"

echo "==> Создаю релиз '$TAG' в $REPO ..."
RESPONSE="$(curl -sS -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/releases" \
  -d "$(jq -n --arg tag "$TAG" --arg name "$TAG" --arg body "$NOTES" \
        '{tag_name:$tag, name:$name, body:$body, draft:false}')")"

RELEASE_ID="$(echo "$RESPONSE" | jq -r '.id')"
if [ "$RELEASE_ID" = "null" ] || [ -z "$RELEASE_ID" ]; then
  echo "Не удалось создать релиз. Ответ GitHub:" >&2
  echo "$RESPONSE" >&2
  exit 1
fi

echo "==> Загружаю $APK_NAME ..."
CODE="$(curl -sS -o /dev/null -w '%{http_code}' -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary "@$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$RELEASE_ID/assets?name=$APK_NAME")"

if [ "$CODE" != "201" ]; then
  echo "Ошибка загрузки APK (HTTP $CODE)." >&2
  exit 1
fi

echo "Готово: https://github.com/$REPO/releases/tag/$TAG"
