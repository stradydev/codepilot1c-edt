# edt-plugin-agent — ротация 2026-08-10 (пятая за день)

Ты — **ротированная сессия `edt-plugin-agent`**, cwd `C:\1C\Dudko\Repos\codepilot1c-edt`,
ветка `pd/mcp-bridge-lite`. Загрузи `CLAUDE.md` (+ `CLAUDE.local.md` → чартер роли) и
`MEMORY.md` как обычно. Этот файл — транзиентное состояние; durable-правила уже в памяти.

**Owner дал автономию:** «действую дальше автономно». Работай сам, отчитывайся пост-фактум.
Гейты сохраняются: push/PR только по явному «send», чартер не самоправить.

## Чем занимались

Тот же метод — проба до кода. За эту сессию он окупился ещё раз, и в самом дешёвом варианте:
две заявки из трёх пунктов BF-11156 оказались наполовину неверны, а один просимый **новый тул**
уже существует — это выяснилось одним живым вызовом, без сборки.

## Сделано (5 коммитов, ничего не пушилось)

Тип `7abbf79`, поверх `e0e8643`:

| Коммит | Round | Что |
|---|---|---|
| `b5cccfb` | 21 | **`create_metadata kind=CommonModule` больше не рождает невалидный модуль.** Без флагов окружения все четыре остаются `false` (ecore-дефолт) → EDT сразу даёт 4 `md-legacy-emf-check` + `common-module-type`. Дефолт = каноничный «Server module» (`clientOrdinaryApplication`+`server`+`externalConnection`), снят с `CommonModuleTypes.SERVER`. Гейт «всё или ничего»: любой указанный флаг окружения отменяет дефолт целиком; `serverCall` окружением НЕ считается (чек его не принимает вместо окружения). Дефолт применяется **до** `applyTopLevelProperties`, поэтому явное значение всё равно кладётся последним. `author_yaxunit_tests` теперь заявляет client+server при создании, а не правит после. `CommonModuleDefaultsTest` 9/9 |
| `4fb348a` | 22 | **`update_infobase`/`launch_app`: в описаниях появилось, ЧТО standalone-тула нет.** Половина заявки опровергнута: литералы уже были на поверхности (таблица требований + описание поллера). Не хватало ровно фразы «вызывать как `edt_diagnostics command=…`» — теперь из одного общего `describeInvocation()` у диспетчера и у поллера. Попутно: таблица требований рендерилась в произвольном порядке (`Map.copyOf` убил `LinkedHashMap`) |
| `05aa9ab` | 23 | **`edt_validate_request` больше не выдаёт токен на операцию, которую этот endpoint не исполнит.** Проверка идёт по **диспетчерскому** тулу (`dcs_upsert_parameter`→`dcs_manage`), fail-open когда роутер не дал предикат видимости. Грабли: видимость — request-scoped ThreadLocal, а `doExecute` уходит в `supplyAsync` ⇒ предикат снимается на вызывающем потоке, иначе гейт не сработал бы никогда. `EndpointOperationGateTest` 7/7 |
| `e24d7fc` | 24 | **BF-11156: два пункта закрыты, третий опровергнут.** (1) `include_check_help=true` больше не выбрасывает молча правила без bundled-описания — они названы в ОДНОЙ сводной записи («это не сбой поиска»). (2) Схлопнутая группа больше не прячет короткие варианты: лимит теперь по символам, а не по числу вариантов, и блок по построению не превышает то, что позволял старый лимит 3×160 (тест это пиннит по всем длинам). Оба правила в core (`CheckHelpDetails`, `DiagnosticGroupSamples`), UI делегирует. 8/8 и 9/9 |
| `7abbf79` | 25 | **`edt_validate_request` принимает `payload` строкой.** Схема объявляет `["object","string"]` — иначе клиент MCP срежет аргумент не того типа и фикс был бы мёртвым. Числа конвертируются как в `ToolArgumentParser` (целое остаётся Integer/Long: `String.valueOf` от Double превратил бы `length:150` в `"150.0"`). `JsonObjectPayloadTest` 9/9 + `EdtValidateRequestPayloadCoercionTest` 4/4 через реальный `execute` |

Собранный билд: **`0.1.7.20260810-0953`** в
`repositories/com.codepilot1c.update/target/repository/plugins/` — содержит Round-17…25,
т.е. **все девять** ждущих живой валидации фиксов.

## НЕМЕДЛЕННЫЙ RESUME

1. **Один редеплой закрывает ДЕВЯТЬ живых валидаций.** Агенту редеплой запрещён классификатором
   (проверено трижды), проси owner'а:
   `! pwsh -NoProfile -File C:\1C\Dudko\Repos\codepilot1c-edt\redeploy-1529.ps1`
   Скрипт сам подхватывает свежайший qualifier. Убедись по `get_workspace_state` →
   `plugin_version` (файл на диске ничего не доказывает, [[sandbox_multiendpoint_ports]]). Проверять:
   - **`path_contains`** (Round-16): `get_diagnostics` project=`Accounting management`
     `scope=project severity=error path_contains='CommonModules/AccessManagement'` → единицы вместо
     6402; контроль `'ZzzNoSuchModule'` → 0.
   - **`baseline`** (Round-18): на `TestConfiguration` `baseline=save` → нота + файл в
     `<workspace>/.codepilot/diagnostics-baseline/`; сразу `diff` → 0 новых + «N pre-existing»;
     затем внеси заведомую ошибку и `diff` → только она.
   - **`delete_metadata`** (Round-17): `create_metadata` SessionParameter → `delete_metadata` без
     `force` → удаляется. Затем CommonModule без `force`: отказ по `recursive`, и он НАЗЫВАЕТ
     containment (это ответ на открытый вопрос 2a в `issues/2026-08-10-sandbox-probe-findings.md`
     — впиши его туда).
   - **`bsl_object_context`** (Round-19) + **`moduleType`** (Round-20): `Document.CasinoCashflowTransactions`
     в `Accounting management` → оба модуля с `context`/`methods`, и типы обязаны быть
     `OBJECT_MODULE`/`MANAGER_MODULE`/`FORM_MODULE`. **Если все три пустые/отсутствуют** — конвертер
     живьём не достался ни из Xtext-инжектора, ни из OSGi; добавляй третий источник (Guice-инжектор
     dt.core через рефлексию, шаблон — `PublishDelegateRegistryResolver`). Корректность это не ломает
     (unknown ≠ ложный `COMMON_MODULE`), фичу — да.
   - **CommonModule-дефолт** (Round-21, НОВОЕ): `create_metadata kind=CommonModule name=EnvDefaultProbe`
     **без properties** → `get_diagnostics` на нём обязан дать **0 ошибок** (до фикса было 5), а
     `edt_metadata_details ... full=true` — показать `clientOrdinaryApplication/server/externalConnection`
     = true. Контроль явного выбора: создать с `properties={"clientManagedApplication":true}` → должен
     остаться ТОЛЬКО этот флаг, дефолт не примешался. Помни грабли Round-20: маркер может
     ОТСУТСТВОВАТЬ, 0/0/0 не отличить от чистого ([[get_diagnostics_cold_edt_gotcha]]) — сверяйся
     с `.mdo`/`edt_metadata_details`, а не только со сканом. Пробы потом удали.
   - **описания** (Round-22/24): `discover_tools category=diagnostics` → в описании `edt_diagnostics`
     видна фраза про `edt_diagnostics command=update_infobase`; описание `edt_metadata_details`
     упоминает `full=true`.
   - **`payload` строкой** (Round-25): `edt_validate_request` с `payload` как JSON-**строка** →
     `valid:true` (а не «payload must be an object»). Это же проверяет, что клиент не срезал строку
     по типу — если срезал, придёт «got nothing», и тогда схему надо смотреть заново.
   - **endpoint-гейт** (Round-23): нужен порт с урезанным профилем; на дефолтном 8763 гейт fail-open,
     так что «valid:true» там — ожидаемо, а не провал. Если такого порта нет — оставь pending.
   После подтверждений допиши статусы в CHANGELOG (в Round-16…25 сейчас «Live validation … pending»).

2. Остаток бэклога — ниже.

## Состояние бэклога фидбэка

**Закрыто кодом (ждут только живой валидации, потом в `processed/`, [[feedback_processed_archive_rule]]):**
`…-token-volume-no-path-filter-or-baseline-diff` (R16+18),
`…-bsl-object-context-false-missing-modules-existing-files` (R19),
`…-update-infobase-launch-app-not-directly-toolsearch-discoverable` (R22),
`…-bf11156-diagnostics-and-metadata-discovery-gaps` (R24, пункты 1+2 фикс, 3 опровергнут),
`…-ensure-module-artifact-validates-but-gated-unreachable` (R23).

**Разобрано, ждёт решения «закрыть разбором»:** `…-new-module-context-staleness-no-reindex`
(разбор в `issues/2026-08-10-new-module-staleness-probe-findings.md`; просимый caveat был бы ложью,
а найденный там реальный дефект уже пофикшен в R21).

**Частично:** `…-edt-validate-request-no-container-semantic-check` — ask 2 (payload строкой) сделан
в R25; **ask 1 (container-проверка на этапе валидации) НЕ сделан осознанно**: apply-time проверка это
`parentItem instanceof FormItemContainer` по **загруженной** форме, т.е. вынести правило нельзя —
нужно открывать форму в валидаторе. Это фича, а не рефакторинг; и она всё равно не валидируется без
редеплоя. Решение о её объёме — за owner'ом.

**Не тронуто:** BF-12338 п.2 (`dcs-ql-hub` false-positives на SDBL date-функциях — **id чека нет
нигде в нашем коде, значит это EDT'шный чек**; наш максимум — суппресс, сначала нужна проба),
`…-no-generic-rename-metadata-affordance`, `…-rights-manage-cannot-set-rls-restriction-condition`.

## Метод (не изобретай заново)

Каждую заявку воспроизводи/перечитывай **до** правки кода — за пять сессий это восемь раз отменило
или переписало работу. Два механизма ложного «этого нет» — в [[zero_build_probe_before_fix]].
Сверяйся с git log и CHANGELOG до заведения работы ([[bus_drain_wave_2026_07_28]]).
Гипотезу о поведении EDT заземляй декомпиляцией ([[edt_diagnostic_build_round_method]]).
**Новое за эту сессию:** прежде чем писать «тула нет» — вызови существующий с флагом `full`.
Просьба о новом туле дважды за две сессии оказывалась незнанием параметра.

## Песочница

`workspace-sandbox`, крутится `0.1.7.20260810-0712`, оба проекта READY, `Accounting management`
привязан к `File_am_sandbox`. **Чисто:** за эту сессию НИЧЕГО не создавал и не менял — только
read-only вызовы (`scan_metadata_index`, `edt_metadata_details`). Единственный след с прошлой сессии
— живой маркер `common-module-type` на `CommonModule.OK`, сбросится редеплоем.
Грабли — [[sandbox_multiendpoint_ports]].

## Bus

Твой id — `edt`, версия логики шины по доке — **7** (всегда читай `bus-logic-version` из
`docs/agent-bus.md`, он в `C:\1C\Dudko\Claude\docs\agent-bus.md`; корень шины —
`C:\1C\Dudko\ClaudeAgents\bus`). Петля отпущена перед выходом, Monitor'ы остановлены.
Подними заново: `Set-BusLoop.ps1 -Action claim -Agent edt -Version 7 -MonitorTaskId pending` →
persistent Monitor на `Watch-AgentInbox.ps1 -Agent edt` → `heartbeat` с реальным task-id.
**Правильное имя скрипта — `Watch-AgentInbox.ps1`** (не `Watch-BusInbox.ps1`, такого нет — на нём
я потерял один заход). Инбокс за эту сессию был пуст. Процедура — [[bus_go_bus_command]];
входящие задачи автономно с пост-фактум отчётом ([[bus_inbound_autonomous_policy]]).

## Указатели

- Фидбэк-ноты: `C:\1C\Dudko\Claude\codepilot1c-feedback\` (закрытые — в `processed/`).
- CHANGELOG — единственное место для notable changes ([[repo_changelog_convention]]).
- Сборка: `mvn -B -Plocal-target -DskipTests clean verify`; один тест-класс — `-Dtest=Class`,
  несколько — **через запятую**; `BUILD SUCCESS` не значит, что тесты шли — читай
  `target/surefire-reports/*.txt` ([[tycho_test_filter_full_reactor]], [[build_local_target_profile]]).
  Известные env-падения — не твои регрессии ([[core_tests_env_failures]]).
- Коммит-сообщения: писать в файл и `git commit -F` ([[powershell_commit_message_rules]]);
  трейлер `Co-Authored-By: Claude Opus 5`.
- Лаунчер для этой роли — **без `-Stack`** ([[launcher_edt_rotation_gap]]).
