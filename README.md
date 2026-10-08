# Yamshchik

Сервис технологических email-рассылок: принимает письмо по HTTP, сохраняет его в очередь и отправляет по SMTP.

## Что нужно

- JDK 25
- Docker с Compose — для PostgreSQL, хранилища вложений MinIO и почтового сервера-ловушки

Maven ставить не нужно, в проекте есть обёртка `mvnw`.

## Запуск

Всё в контейнерах, одной командой (первая сборка образа идёт несколько минут):

```bash
docker compose --profile app up -d --build --wait   # приложение на http://localhost:8080
```

Или окружение в контейнерах, а приложение из исходников — так удобнее при разработке:

```bash
docker compose up -d --wait   # PostgreSQL на 5432, MinIO на 9000 (консоль на 9001), Mailpit на 1025 (SMTP) и 8025 (веб-интерфейс)
./mvnw spring-boot:run        # приложение на http://localhost:8080
```

Из IntelliJ IDEA — готовая конфигурация запуска «Yamshchik (local)» (файл `.run/Yamshchik (local).run.xml`).

Все три способа используют профиль `local` и занимают порт 8080, одновременно их не запускать.

Схема БД создаётся при старте приложения (Liquibase).
Отправленные письма видны в Mailpit: http://localhost:8025.

Вложения лежат в MinIO в бакете `yamshchik`; он создаётся вместе с контейнером. Консоль: http://localhost:9001,
учётная запись `yamshchik` / `yamshchik-secret`.

Остановить окружение: `docker compose down`, вместе с данными БД и вложениями — `docker compose down -v`.

## Проверка

Письмо с текстом, HTML, встроенной картинкой и вложением:

```bash
cat > email.json <<'EOF'
{
  "from": {"address": "sender@example.com", "name": "Ямщик"},
  "to": [{"address": "user@example.com", "name": "Иван Иванов"}],
  "subject": "Отчёт готов",
  "body": {
    "text": "Текстовый вариант",
    "html": "<p>HTML-вариант <img src=\"cid:logo\"></p>"
  },
  "attachmentOptions": [{"filename": "logo.png", "disposition": "INLINE", "contentId": "logo"}]
}
EOF

curl -X POST http://localhost:8080/api/v1/emails \
  -F "email=@email.json;type=application/json" \
  -F "attachments=@logo.png;type=image/png" \
  -F "attachments=@report.pdf;type=application/pdf"
```

В ответе — идентификатор письма и статус `ACCEPTED`. Состояние письма:

```bash
curl http://localhost:8080/api/v1/emails/{id}
```

Статусы: `ACCEPTED` → `SENDING` → `SENT` либо `FAILED`. После временного отказа письмо уходит в `DEFERRED`
и ждёт следующей попытки (`nextAttemptAt`); после постоянного отказа или последней попытки — в `FAILED`.

## Настройки

Настройки разделены на два файла в `src/main/resources`:

- `application.yaml` — общее для всех стендов: поведение очереди, повторы, лимиты приёма, метрики;
- `application-local.yaml` — то, что зависит от стенда: адреса и учётные записи БД, почтового сервера
  и хранилища вложений. Значения рассчитаны на `compose.yaml`.

Без профиля приложение не стартует: адресов служб в общем файле нет. Для другого стенда нужен свой
`application-<стенд>.yaml` или переменные окружения Spring (`SPRING_DATASOURCE_URL`, `SPRING_MAIL_HOST`,
`YAMSHCHIK_STORAGE_ENDPOINT` и т. д.) — так сделано для приложения в контейнере.

Поведение переопределяется переменными окружения:

| Переменная | По умолчанию | Назначение |
|---|---|---|
| `ALLOWED_SENDER_DOMAINS` | пусто | Домены, с адресов которых разрешено отправлять, через запятую; пусто — без ограничений |
| `RETRY_POLICY` | `exponential` | Повторы: `exponential` — только временные отказы, пауза удваивается; `fixed` — любой отказ через одну и ту же паузу |
| `RETRY_MAX_ATTEMPTS` | `5` | Сколько всего попыток, считая первую |
| `RETRY_INITIAL_DELAY` / `RETRY_MAX_DELAY` | `PT30S` / `PT1H` | Пауза перед второй попыткой и предел её роста |
| `ATTACHMENT_STORAGE` | `s3` | Где лежат вложения: `s3` — объектное хранилище; `filesystem` — каталог на диске экземпляра; `kafka` — топик Kafka (нужен `docker compose --profile kafka up -d --wait`) |
| `DISPATCH_WORKERS` | `4` | Сколько обработчиков одновременно отправляют письма |
| `DISPATCH_QUEUE` | `postgres` | Очередь отправки: `postgres` — в таблице писем, переживает рестарт; `memory` — в памяти процесса, при рестарте ждущие письма остаются в `ACCEPTED` и не отправляются |

Интервал опроса очереди — `yamshchik.dispatch.poll-interval` в `application.yaml`. Там же срок аренды
`yamshchik.dispatch.lease` (5 минут): письмо, итог отправки которого за это время не записан, берётся в отправку снова.

Отказы почтового сервера для проверки повторов задаются в Mailpit, например временный отказ на каждого получателя:
`curl -X PUT http://localhost:8025/api/v1/chaos -d '{"Recipient":{"ErrorCode":451,"Probability":100}}'`;
сброс — тот же запрос с `{}`.
Ограничения на письмо — там же, в `yamshchik.intake`: размер после кодирования (25 МБ), число получателей (100) и вложений (10).
Превышение размера даёт `413`, остальные нарушения — `400`.

## Метрики

`GET /actuator/prometheus` — показатели в формате Prometheus, `GET /actuator/health` — состояние сервиса.

| Метрика | Что показывает |
|---|---|
| `yamshchik_emails_accepted_total` | Писем принято |
| `yamshchik_dispatch_attempts_seconds` | Попытки отправки и их длительность; метки `outcome` (sent, deferred, failed) и `reply` (accepted, 4xx, 5xx, none) |
| `yamshchik_emails_delivery_time_seconds` | Время от приёма письма до приёма его почтовым сервером |
| `yamshchik_emails_by_status` | Сколько писем в каждом статусе; метка `status` |

## API

Контракт описан в `src/main/resources/openapi/yamshchik-api.yaml` и является источником истины:
интерфейсы контроллеров и DTO генерируются из него при сборке. Менять API нужно начиная со спецификации.

## Структура

```
domain/        модель письма и переходы статусов
application/   сценарии и порты (port/in — входящие, port/out — исходящие)
adapter/in/    REST и планировщик очереди
adapter/out/   PostgreSQL, очередь в памяти, хранилище вложений (S3) и SMTP
config/        конфигурации, настройки (properties), исключения (exception)
```

Открытые задачи и принятые решения — в `BACKLOG.md`.
