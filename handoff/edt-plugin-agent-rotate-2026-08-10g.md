# edt-plugin-agent — ротация 2026-08-10 (седьмая за день)

Ты — **ротированная сессия `edt-plugin-agent`**, cwd `C:\1C\Dudko\Repos\codepilot1c-edt`,
ветка `pd/mcp-bridge-lite`. Загрузи `CLAUDE.md` (+ `CLAUDE.local.md` → чартер роли) и
`MEMORY.md` как обычно. Этот файл — транзиентное состояние; durable-правила уже в памяти.

**Owner дал автономию:** «действую дальше автономно». Работай сам, отчитывайся пост-фактум.
Гейты сохраняются: push/PR только по явному «send», чартер не самоправить.

## Главное за сессию

**Регрессия `bsl_object_context` закрыта — она никогда не была зависанием.** Это была арифметика:
ровно 60 с на объекте с одним модулем и ровно 120 с на документе с двумя, а клиент MCP отваливался
раньше. Каждый делегирующий вызов сжигал ровно 30 с в `ServiceTracker.waitForService` за
`BmAwareResourceSetProvider`, после чего падал в standalone-resource-set и отвечал правильно.

**Ждать этот сервис бессмысленно в принципе:** байткод активатора `com._1c.g5.v8.dt.bm.xtext`
публикует ровно 4 OSGi-сервиса, и провайдера среди них нет — он привязан в `CoreModule` того же
бандла как Xtext'овый `IResourceSetProvider`, т.е. это **Guice-инжектор**. Живое подтверждение: в
логе песочницы 21 запись «service not available after wait (30000 ms)», **все 21 — про этот сервис
и ни одной про любой другой**, за шестичасовую сессию с обоими проектами READY.

**Метод, который это дал за один заход:** `jstack` по живому EDT. Песочный процесс опознаётся по
`-data file:/…/workspace-sandbox/` в командной строке (сейчас PID 4152, но проверяй заново),
JDK Zulu 17 лежит на машине, EDT — HotSpot 17, attach работает. Диагностическая сборка **не
понадобилась**. Это теперь первый ход для любого live-only симптома, который можно удержать
открытым: вызов в фон через `mcp-call.ps1` → `jstack <pid>` → grep по `codepilot1c`.

## Сделано (2 коммита, ничего не пушилось)

Тип `4b79277`, поверх `8d5dc01`:

| Коммит | Что |
|---|---|
| `dede736` | **Round-27.** `VibeCorePlugin.getResourceSetProvider()` больше не блокирует: это теперь неблокирующий lookup реестра, доказательства — в javadoc, плюс одна запись в лог за сессию вместо 21 одинаковой. Остальные сервисы 30-секундную льготу сохранили (они реально появляются на старте, и ни один не таймаутил). Латентность: 30 с на каждый `bsl_*` вызов, 30 с × модулей × 2 в `bsl_object_context`, 30 с **на каждую найденную ссылку** в `edt_find_references`, 30 с на каждый lookup в `inspect_platform_reference`. Ответы не менялись — все четыре потребителя и раньше деградировали по `null`. Юнит-теста нет **осознанно**: свойство «не блокирует внутри живого OSGi-реестра» в реакторе не наблюдаемо (при `null`-трекере старый код тоже возвращался мгновенно, тест прошёл бы и до фикса) |
| `4b79277` | **Round-28.** `rights_manage`: грант-уровневый `fields` теперь отклоняется, а не выбрасывается. Round-26 обещал отказ, но он работал только для вложенного написания `restriction: [{condition, fields}]`; плоское `{object_fqn, right, fields, restriction}` — то, что пишут первым — давало `valid:true`, и нормализация ключ срезала, после чего токен о нём уже не знал. Отказ вынесен в общий `refuseFieldLevelRls`, вызывается из обоих написаний и из `parseRestrictions` **до** раннего return (так что `fields` вообще без `restriction` тоже отклоняется). `edt_validate_request` нормализует через тот же `parseGrants` ⇒ один guard закрывает оба этапа. `RightsManageRestrictionTest` 21/21. Плюс поправлено описание `include_check_help` (обещало старое «silently omitted») |

**Сборка сделана и зелёная** — jar'ы в `bundles/*/target/` соответствуют ОБОИМ коммитам
(последний прогон: `-Dtest=RightsManageRestrictionTest … clean verify` → BUILD SUCCESS, 21/21).

## Живая валидация на сборке `-1011` (та, что стоит в песочнице)

**Подтверждено:**
- **Round-18 baseline**, все три шага: `save` → файл
  `<workspace>/.codepilot/diagnostics-baseline/TestConfiguration.txt` + «27 recorded»; сразу `diff` →
  0 при «27 pre-existing not shown»; заведомая ошибка → `diff` показал **только** её 3 записи.
- **Round-24 check-help**: правила с help получили полное описание; `md-legacy-emf-check` (help не
  поставляется) **назван** одной сводной записью, а не выброшен; схлопнутая группа `×3` короткий
  вариант не спрятала.
- **Round-26 RLS**, от начала до конца на `Role.AddEditAlertsTypes` в песочном AM: условие легло
  внутрь гранта `Read`; повтор того же запроса → «nothing was written … unchanged», второго
  `<restrictionByCondition>` нет; `restriction: []` стёр, и файл вернулся **байт-в-байт** к исходному
  SHA-256 (значит и EOL-сохранение держится); `value:remove` + `restriction` отклонён на валидации.

**Найдено:**
- **Щель Round-26 с `fields`** — исправлена (`4b79277`, см. выше).
- **`get_diagnostics` считает один дефект дважды.** Одна BSL-ошибка при дефолтных настройках даёт
  «2 errors»: workspace-маркер и runtime-маркер оба её сообщают, а их ключи дедупликации не могут
  совпасть (разные формы ключа И разные пути). НЕ починено, разобрано и описано:
  `issues/2026-08-10-get-diagnostics-double-counts-across-marker-sources.md`. **Следствие: все
  записанные числа из нефильтрованных сканов надо перечитывать с этой поправкой**, включая «3 errors»
  из проверки Round-16.

## НЕМЕДЛЕННЫЙ RESUME

1. **Round-23 endpoint-гейт — единственный оставшийся пункт чек-листа, и он готов к проверке.**
   Точная связка найдена: `ensure_module_artifact` лежит в `disableTools` профиля **`dev`** ⇒ порт
   **8765** (Bearer `<redacted>`) — это гейтованный endpoint для
   него, а 8763 (`full`, токен в `mcp-call.ps1`) токен выдать обязан. Профили — в
   `~/.codepilot1c/mcp-profiles.json` (порты: 8764 orchestrator, 8765 dev, 8766 qa, 8767 infra).
   Ожидание: `edt_validate_request operation=ensure_module_artifact` на 8765 → отказ, на 8763 → токен.
   `mcp-call.ps1` в корне репо ходит только на 8763 — для другого порта скопируй его и подмени
   `$McpUrl`/`$McpToken`.
2. **Попроси owner'а редеплой и замерь Round-27 живьём** — это единственная валидация фикса:
   `! pwsh -NoProfile -File C:\1C\Dudko\Repos\codepilot1c-edt\redeploy-1529.ps1` (берёт свежайший jar
   по времени; **jar уже собран**, ничего пересобирать не нужно). После редеплоя: `bsl_module_context`
   на `TestConfiguration/CommonModules/OK/Module.bsl` должен вернуться **мгновенно** (было ровно 30 с),
   `bsl_object_context` на `CommonModule.OK` — мгновенно (было ровно 60 с). Замеряй по часам, а не «на
   глаз»: именно «кажется быстро» без замера и спрятало этот дефект на целую сессию.
   В логе `C:\Users\pavel.dudko\workspace-sandbox\.metadata\.log` после редеплоя должна появиться
   **одна** запись про «not an OSGi service» вместо накопления «after wait (30000 ms)».
3. Дальше — бэклог ниже.

## Состояние бэклога фидбэка

**Закрыто кодом + ЖИВЬЁ подтверждено** (можно двигать в `processed/`,
[[feedback_processed_archive_rule]]): `…-token-volume-no-path-filter-or-baseline-diff` (теперь
**полностью**: path_contains ✅ + baseline ✅), `…-update-infobase-launch-app-not-directly-toolsearch-discoverable`,
`…-bf11156-diagnostics-and-metadata-discovery-gaps` (R24 ✅),
`…-rights-manage-cannot-set-rls-restriction-condition` (R26 ✅ + R28 закрыл щель).

**Закрыто кодом, живая валидация ждёт:** `…-ensure-module-artifact-validates-but-gated-unreachable`
(R23 — см. resume п.1), Round-27 (см. resume п.2).

**`…-bsl-object-context-false-missing-modules-existing-files`** — код закрыт (R19 + R27), **но
закрывать ноту рано**: после редеплоя убедись, что `bsl_object_context` реально возвращает модули, а
не только быстро. И учти вторую половину: `owner` у модуля **не резолвится никогда** (standalone
resource set не имеет BM-связи) — отдельная нота
`issues/2026-08-10-bsl-module-owner-unresolved-standalone-resource-set.md`, там уже заземлены все
факты декомпиляции и один открытый вопрос («какой инжектор ставит `CoreModule`»), который решает объём
фикса. Два тупика там же помечены как проверенные — не перепроверяй.

**Разобрано, ждёт решения «закрыть разбором»:** `…-new-module-context-staleness-no-reindex`.

**Частично:** `…-edt-validate-request-no-container-semantic-check` — ask 2 сделан (R25); ask 1
осознанно НЕ сделан (нужна загрузка формы в валидаторе — это фича, объём за owner'ом).

**Не тронуто:**
- **BF-12338 п.2** (`dcs-ql-hub` на SDBL date-функциях) — id чека нет нигде в нашем коде ⇒ чек EDT'шный,
  наш максимум суппресс, и сначала нужна проба. (п.1 уже пофикшен, из «не тронуто» вычеркнут.)
- **`…-no-generic-rename-metadata-affordance`** — заявка корректна, молчаливой порчи НЕТ
  (`update_metadata` отказывает явно). Механика есть: `transaction.updateTopObjectFqn` + очистка
  старого storage, EDT свой move гоняет через `IRefactoringService.initiateRename`. Дорогая часть —
  перепривязка ссылок (BSL-текст, `.rights`, состав подсистем). Сначала реши, делегируем ли целиком
  EDT'шному refactoring-сервису — это решает объём.
- **`get_diagnostics` двойной подсчёт** — новая нота, см. выше.

## Метод (не изобретай заново)

`jstack` по живому процессу вместо диагностической сборки — см. «Главное». **«Кажется быстро» — не
замер:** предшественник записал `bsl_module_context` как «well under a second», по часам это ровно
30 с, и именно эта ошибка измерения спрятала причину, а четыре гипотезы искали её не там. Три (теперь
четыре) механизма ложного «этого нет» — [[zero_build_probe_before_fix]]. Новое поле мутирующего тула —
[[validation_token_canonical_payload_trap]] (правь 4 места, тестируй по НОРМАЛИЗОВАННОМУ payload);
R28 — тот же капкан в зеркальном виде: нормализация срезала ключ, который должен был вызвать ОТКАЗ.
Гипотезу о поведении EDT заземляй декомпиляцией ([[edt_diagnostic_build_round_method]]): в этой сессии
байткод активатора `bm.xtext` за минуту закрыл вопрос, который иначе гадался бы сборками.
`javap -p -c` из Zulu 17 достаточно, полноценный декомпилятор не нужен.

## Песочница

`workspace-sandbox`, сборка **`0.1.7.20260810-1011`** (Round-16…26; R27/R28 ещё НЕ установлены),
оба проекта READY, `Accounting management` привязан к `File_am_sandbox`
(`C:\Users\pavel.dudko\git-sandbox\am\Accounting management`).
**Чисто:** роль `AddEditAlertsTypes` вернулась байт-в-байт (`git status` пуст),
`TestConfiguration/CommonModules/OK/Module.bsl` возвращён в пустой вид. Остаётся артефакт фичи —
`.codepilot/diagnostics-baseline/TestConfiguration.txt`: он валиден для восстановленного состояния,
удалять не нужно, но помни, что baseline там записан. Живут предсуществующие ошибки обоих проектов —
**не твои**. Грабли — [[sandbox_multiendpoint_ports]].

## Bus

Твой id — `edt`, версия логики шины — **7** (всегда читай `bus-logic-version` из
`C:\1C\Dudko\Claude\docs\agent-bus.md`; корень шины — `C:\1C\Dudko\ClaudeAgents\bus`).
Петля отпущена перед выходом, Monitor'ы остановлены. Подними заново:
`Set-BusLoop.ps1 -Action claim -Agent edt -Version 7 -MonitorTaskId pending` → persistent Monitor на
`Watch-AgentInbox.ps1 -Agent edt` → `heartbeat` с реальным task-id. За сессию пришло одно сообщение
(rotate-отчёт предшественника), сдренено в `processed/`, inbox пуст.
Процедура — [[bus_go_bus_command]]; входящие задачи автономно с пост-фактум отчётом
([[bus_inbound_autonomous_policy]]).

## Указатели

- Фидбэк-ноты: `C:\1C\Dudko\Claude\codepilot1c-feedback\` (закрытые — в `processed/`).
- CHANGELOG — единственное место для notable changes ([[repo_changelog_convention]]).
- Сборка: `mvn -B -f C:\1C\Dudko\Repos\codepilot1c-edt\pom.xml -Plocal-target -DskipTests clean verify`.
  Один тест-класс — `-Dtest=Class` (несколько — через запятую, **всю связку квотить**: `'-Dtest=A,B'`).
  `BUILD SUCCESS` не значит, что тесты шли ([[tycho_test_filter_full_reactor]], [[build_local_target_profile]]).
  Известные env-падения — не твои ([[core_tests_env_failures]]).
- **PowerShell-инструмент, не Bash**, для PowerShell-команд: Bash-обёртка съедает `$_`
  (`Get-Process | Where-Object { $_.X }` превращается в мусор). Проверено в этой сессии.
- Коммит-сообщения: писать в файл и `git commit -F` ([[powershell_commit_message_rules]]);
  трейлер `Co-Authored-By: Claude Opus 5`.
- Лаунчер для этой роли — **без `-Stack`** ([[launcher_edt_rotation_gap]]).
