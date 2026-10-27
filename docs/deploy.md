# CI/CD и окружения

## CI

Workflow `CI` (`.github/workflows/ci.yml`) запускается на каждый pull request и push в `main`:

1. `spotlessCheck` проверяет форматирование.
2. `build` собирает проект и запускает тесты. Тесты поднимают PostgreSQL в Testcontainers и применяют все миграции Flyway, поэтому сломанная миграция роняет CI.
3. Проверяется, что собирается Docker-образ.

## Окружения

| Окружение | Когда разворачивается | Профиль Spring |
|-----------|-----------------------|----------------|
| `dev`     | каждый push в `main` | `dev` |
| `stage`   | тег `v*` (например, `v0.1.0`) или вручную: Actions → Deploy → Run workflow | `stage` |

Workflow `Deploy` собирает образ, публикует его в `ghcr.io/trkpo/goals_app_backend` с тегом SHA коммита и по SSH обновляет сервер окружения: `docker compose pull && up -d`. На сервере работают три контейнера: `app`, `db` (PostgreSQL) и `caddy`. Caddy сам выпускает и продлевает сертификат Let's Encrypt, включает HTTPS и редирект с HTTP (`NF_Sec_3`).

Пока окружение не настроено, деплой пропускается с предупреждением, и CI не падает.

## Настройка окружения

**Сервер.** Linux с Docker и плагином Compose. Открыты порты 80 и 443. DNS-запись домена указывает на сервер. Пользователь для деплоя входит в группу `docker` и может писать в `/opt/goals-app`.

**GitHub.** Settings → Environments → `dev` / `stage`.

Секреты (Secrets):

| Имя | Что это |
|-----|---------|
| `SSH_HOST` | адрес сервера |
| `SSH_USER` | пользователь для деплоя |
| `SSH_PRIVATE_KEY` | приватный ключ, публичная часть — в `~/.ssh/authorized_keys` на сервере |
| `SSH_KNOWN_HOSTS` | вывод `ssh-keyscan <SSH_HOST>` |
| `DB_PASSWORD` | пароль PostgreSQL |

Переменные (Variables):

| Имя | Что это |
|-----|---------|
| `DOMAIN` | домен окружения, например `stage.goals.example.com` |
| `APP_URL` | ссылка для страницы деплоя, например `https://stage.goals.example.com` |

Для `stage` стоит включить Required reviewers, чтобы деплой шёл только после подтверждения.

## Секреты

В репозитории секретов нет. На сервере файл `.env` создаёт workflow с правами `600`. Для локального запуска скопируйте `.env.example` в `.env`; этот файл в `.gitignore`.
