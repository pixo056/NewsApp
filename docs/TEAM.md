# Карта проекта для команды

| Область                                       | Где работать                                                                                                          |
| --------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- |
| React: лента, категории, поиск, паблики       | src/pages/ReaderPages.tsx, src/components/ui.tsx                                                                      |
| React: автор, профиль, авторизация, модерация | src/pages/WorkspacePages.tsx                                                                                          |
| CMS: React-admin и подключение API            | src/cms, backend/.../CmsController.java, CmsService.java, CmsUsersController.java, CmsUsersService.java               |
| API и сессия                                  | src/api, src/app/AppContext.tsx, src/app/context.ts                                                                   |
| Java: аккаунты и доступы                      | backend/.../Accounts.java, AuthController.java, SecurityConfig.java                                                   |
| Java: новости/паблики/модерация               | backend/.../NewsService.java, Communities.java, PlatformController.java                                               |
| Медиа                                         | backend/.../MediaService.java, MediaController.java                                                                   |
| Реакции                                       | src/components/ReactionPicker.tsx, src/domain/reactions.ts, backend/.../ReactionService.java, ReactionController.java |
| PostgreSQL                                    | backend/src/main/resources/db/migration                                                                               |
| Проверки/локальный запуск                     | backend/src/test, scripts                                                                                             |
| Android/iOS                                   | android, ios, capacitor.config.ts, docs/MOBILE.md                                                                     |

Применённые миграции V1–V4 не переписывайте: добавляйте следующую по номеру. V4 хранит вид реакции, сохраняет прежние лайки и ограничение одного выбора аккаунта на новость. При изменении DTO обновляйте TypeScript-типы и HTTP-тест. После Java-правок остановите приложение и запустите снова; React обновляется через Vite.

Для демонстрации пройдите README и HANDOFF. Используйте независимые сессии автора, владельца обычного паблика и ADMIN. Владелец модерирует все нескрытые заявки/новости и жалобы своих обычных пабликов. Личные и официальные материалы — у ADMIN, который также может вмешаться в остальные. Пароли только в .local/accounts.txt.

Нативные оболочки требуют отдельной сборки и проверки в Android Studio/Xcode; готовность web не означает проверку APK/IPA. Интеграционные тесты и HTTP smoke уже включены в проект, результат обновлять после значимых изменений.
