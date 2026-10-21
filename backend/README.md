# Spring Boot backend

Java **25**, Spring Boot **3.5.16**, Maven, Spring Web/Security/JDBC/Validation, PostgreSQL и Flyway. Для SQL используется Spring JDBC с параметризованными запросами и транзакциями. JPA/Hibernate, Kafka и микросервисы не добавлялись.

Главный класс: `ru.uunit.news.NewsApplication`. Порт: `127.0.0.1:8080`. Общий запуск — `../start.cmd`.

Компоненты:

- `Accounts`, `AuthController`, `SecurityConfig` — аккаунты, сессии, подтверждение и сброс пароля.
- `Communities` — владельцы пабликов, официальный статус и лимиты.
- `NewsService`, `PlatformController` — лента, публикации/ревизии, предмодерация, подписки, сохранения, реакции, жалобы и аудит.
- `MediaService` — проверка содержимого, фото, FFmpeg/ffprobe, приватная выдача и HTTP Range.
- `Seeder` — создание двух локальных аккаунтов и стартовых материалов один раз.
- `db/migration/V1__schema.sql` — схема PostgreSQL.

Переменные окружения и команды разработки — в корневом README. Секреты не записываются в исходники.

`scripts/test-backend.ps1` запускает интеграционные и модульные тесты с настоящей PostgreSQL в отдельной базе `newsapp_test`, включая запрет удалённого доступа к тестовой почте. `scripts/smoke-local.mjs` проверяет настоящий HTTP-путь через frontend proxy.
