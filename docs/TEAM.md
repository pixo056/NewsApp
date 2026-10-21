# Карта проекта для команды

| Область                                       | Где работать                                                            |
| --------------------------------------------- | ----------------------------------------------------------------------- |
| React: лента, категории, поиск, паблики       | src/pages/ReaderPages.tsx, src/components/ui.tsx                        |
| React: автор, профиль, авторизация, модерация | src/pages/WorkspacePages.tsx                                            |
| API и сессия                                  | src/api, src/app/AppContext.tsx, src/app/context.ts                     |
| Java: аккаунты и доступы                      | backend/.../Accounts.java, AuthController.java, SecurityConfig.java     |
| Java: новости/паблики/модерация               | backend/.../NewsService.java, Communities.java, PlatformController.java |
| Медиа                                         | backend/.../MediaService.java, MediaController.java                     |
| PostgreSQL                                    | backend/src/main/resources/db/migration                                 |
| Проверки/локальный запуск                     | backend/src/test, scripts                                               |
| Android/iOS                                   | android, ios, capacitor.config.ts, docs/MOBILE.md                       |

Перед изменением схемы добавляйте новую миграцию V2, V3 и далее: существующую V1 после запуска менять нельзя. При изменении DTO обновляйте TypeScript-типы и HTTP-тест. После Java-правок остановите приложение и запустите снова; React обновляется через Vite.

Для демонстрации сначала пройти полный сценарий в README. Убедиться, что один браузер работает гостем/автором, а второй — создателем обычного паблика либо администрацией. Учётные данные находятся только в .local/accounts.txt.

Нативные оболочки требуют отдельной сборки и проверки в Android Studio/Xcode; готовность web не означает проверку APK/IPA. Интеграционные тесты и HTTP smoke уже включены в проект, результат обновлять после значимых изменений.
