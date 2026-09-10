# Ims

Приложение без UI: через Shizuku получает shell-права и перебивает carrier config
(VoLTE / VoWiFi / VoNR / cross-SIM) для активных SIM.

## Как оно запускается

```
Shizuku присылает биндер
  → ShizukuProvider.call(METHOD_SEND_BINDER)
    → startInstrument()  — am.startInstrumentation в sdk-sandbox процессе
      → PrivilegedProcess.onCreate()  — биндер обратно в провайдер
        → startDelegateShellPermissionIdentity()
          → PrivilegedProcess.overrideConfig()
```

Второй вход — `ConfigChangeReceiver` на `SIM_STATE_CHANGED` / `CARRIER_CONFIG_CHANGED`
и на ручной `io.github.vvb2060.ims.action.APPLY`.

**`startInstrumentation()` делает force-stop нашего же пакета** перед запуском:

```
I/ActivityManager: Force stopping io.github.vvb2060.ims appid=10448 user=0: start instr
I/ActivityManager: Killing 4289:io.github.vvb2060.ims/u0a448 (adj 0): stop … due to start instr
```

Из этого следует всё остальное про состояние между прогонами:

- **любой статик обнуляется на каждом прогоне.** Debounce и счётчик прогонов жили в
  статиках и не работали никогда — цикл обрывал `staleKeys()`, а не они. Всё, что должно
  пережить прогон, лежит на диске (`RunGuard`, SharedPreferences, `commit()` а не
  `apply()` — убивают сразу после `startInstrument()`);
- **после force-stop процесс поднимается заново, Shizuku снова присылает биндер**, и
  провайдер запускает следующий прогон. Поэтому гейт обязан быть общим для обоих входов,
  иначе получается вечный цикл запись → бродкаст → force-stop → биндер → запись.

**GET_BINDER и SEND_BINDER гоняются.** Процесс приложения поднимается под вызов
GET_BINDER из sandbox, и тут же Shizuku присылает в него SEND_BINDER — на другой
binder-поток. Обычный `addBinderReceivedListener` срабатывает только на будущую рассылку:
если SEND_BINDER успевал первым, слушатель GET_BINDER не вызывался никогда, sandbox не
получал `transact` и висел, не написав ни строки. 10.09.2026 так потерялся прогон при
вставке SIM: sandbox прожил полчаса, пока его не снёс следующий прогон
(`Killing …_sdk_sandbox_instr … : instrumentation started` — висящий sandbox следующим
прогонам не мешает). Отсюда `runOnceWithBinder()` в провайдере (sticky + собственная
проверка биндера) и таймаут на `finish()` в `PrivilegedProcess`. Признак осечки в логе —
`run N`, за которым не последовало `cached config files`.

Приложение **не может отработать без запущенного Shizuku**. На тестовом Pixel 10 Pro
Shizuku не стартует после перезагрузки сам — поднимается только когда включён Wi-Fi
(через wireless debugging). Без Wi-Fi после ребута конфиг живёт исключительно за счёт
копии на диске.

## Где живёт carrier config

Два независимых хранилища в `com.android.phone`:

| | `mOverrideConfigs` | `mPersistentOverrideConfigs` |
|---|---|---|
| где | только RAM | RAM + файл на диске |
| после ребута | пусто | восстанавливается |

`overrideConfig(subId, values, persistent)` пишет в RAM **всегда**, на диск — только при
`persistent=true`. `values=null` очищает. Соответственно `persistent=false, overrides=null`
чистит только RAM, диск не трогает.

Порядок склейки при чтении, каждый следующий перекрывает предыдущий:

```
дефолты AOSP → mConfigFromDefaultApp → mConfigFromCarrierApp
             → mPersistentOverrideConfigs → mOverrideConfigs
```

Persistent-слоя достаточно: конфиг работает и при пустом RAM-слое (проверено —
подмена имени оператора действовала с `mOverrideConfigs` = 0 ключей).

### Почему persistent вообще проходит

`overrideConfig()` первым делом зовёт `secureOverrideConfig()`, ещё до записи куда-либо:

```java
if (TelephonyPermissions.isShell(Binder.getCallingUid()))
    throw new SecurityException("overrideConfig cannot be invoked by shell");
...
if (persistent && isUserBuild() && !isSystemApp())
    throw new SecurityException(
        "overrideConfig with persistent=true only can be invoked by system app");
```

`isUserBuild()` = `"user".equals(Build.TYPE)` — на релизной прошивке всегда true.
`isSystemApp()` смотрит флаги пакета, полученного через `getNameForUid(getCallingUid())`.

Мы проходим **только благодаря sdk-sandbox**: вызов идёт с sandbox-uid (`appUid + 10000`),
который резолвится в `com.google.android.sdksandbox` — а он системный. Прямо из процесса
приложения persistent-запись на user-build отлетела бы по SecurityException, причём до
записи, то есть не применилось бы вообще ничего, даже RAM-слой. Отсюда же и Permission
denied у `cmd phone cc` из shell — первая проверка режет вызовы по uid.

Проверять, доступен ли persistent, рефлексией (`canPersistent()`) смысла мало: на этой
прошивке оба метода на месте, и она возвращает true.

### Оверрайд живёт в слоте, а не в симке

Оба массива в `CarrierConfigLoader` индексируются **phoneId (слотом)**, хотя API принимает
subId. При смене SIM AOSP сбрасывает `mConfigFromDefaultApp` / `mConfigFromCarrierApp`, но
override-слои оставляет — они считаются пользовательскими. Итог: записали оператор-специфичные
ключи для симки в слоте 0, вынули её, вставили другую — новая унаследовала чужой конфиг.

Ловилось так: `Phone Id = 0 / mPersistentOverrideConfigs: carrier_name_string = Mts,
sim_country_iso_override_string = us`, при том что в слоте 0 сидела eSIM Cellfie (282/04),
и в `dumpsys isub` у неё уже стояло `carrierName=Mts countryIso=us`.

Поэтому **все оператор-специфичные ключи пишутся всегда**: `getConfig()` кладёт нейтральные
значения (`putCarrierNameDefaults`), `GetMts()` перекрывает их для 25001. Иначе `staleKeys()`
чужой остаток не видит — он сверяет только те ключи, что мы собираемся писать.

Осадок в `siminfo` (`carrierName`) и в `gsm.sim.operator.alpha` после чистки конфига держится
до перечитки SIM records — авиарежим или ребут.

### Почему настройки «слетают выборочно»

Файл на диске: `carrierconfig-<пакет carrier-приложения>-override-<ICCID>-<carrierId>.xml`.

- **По одному файлу на SIM.** Новая SIM / другой eSIM-профиль → своего файла нет → для
  неё конфига нет, для остальных есть.
- **carrierId входит в имя.** Одна и та же симка при неопределившемся carrierId (`-1`)
  ищется под другим именем и не находится.
- **Файл помечен версией carrier-приложения** (`__carrier_config_package_version__`).
  При обновлении `com.google.android.carrier` файлы с чужой версией не восстанавливаются.
- Пользовательские тумблеры (VoLTE / WFC / режим WFC / VoNR) лежат **не** в carrier config,
  а в таблице `siminfo` по subId. Приложение их не трогает — carrier config только
  разрешает их и задаёт дефолты. Новый subId = свежие дефолты.

### Наш файл сносит чистилка кэша carrier-приложения

Главная причина пропаж, и она не про нас. `clearCachedConfigForPackage(pkg)` фильтрует
файлы по префиксу:

```java
name.startsWith("carrierconfig-" + packageName + "-")
```

Под `carrierconfig-com.google.android.carrier-` попадает и наш
`carrierconfig-com.google.android.carrier-override-<ICCID>-<carrierId>.xml`. Сопутствующий
ущерб: файлы самого carrier-приложения тут же регенерируются, наш — нет, его пишет
**только** `overrideConfig(..., persistent=true)`.

Три триггера, все три ведут к `updateConfigForPhoneId()` → `CARRIER_CONFIG_CHANGED`:

| триггер | что сносит |
|---|---|
| `notifyConfigChangedForSubId()` от carrier-приложения | `carrierconfig-<вызывающий пакет>-*` |
| `EVENT_PACKAGE_CHANGED` для carrier-приложения | `carrierconfig-<pkg>-*` |
| смена `build_fingerprint` (OTA) | **все** `carrierconfig-*` |

Первый — рутинный: carrier-приложение зовёт его при смене SIM, доопределении carrierId,
обновлении entitlement. Никакого OTA и обновления пакетов для пропажи не нужно.

**RAM-слои при этом не трогаются** — `clearCachedConfigForPackage` только удаляет файлы.
Поэтому пропажа не видна до ребута: `mOverrideConfigs` цел, склеенный конфиг верный,
а на диске уже пусто.

Так и потерялся конфиг 21.08.2026: три `-override-` файла (были 15.08) исчезли, до ребута
всё работало, после — `mPersistentOverrideConfigs : null` на обоих слотах.

### Когда пишем конфиг

Маркер версии (`bundle.getInt("vvb2060_config_version") != BuildConfig.VERSION_CODE`)
выброшен давно: правки в `getConfig()` без бампа `versionCode` не применялись никогда,
маркер читается из **склеенного** конфига и не замечает чужих перезаписей, а `GetMts()`
под тем же guard'ом не применялся уже никогда, если при первом прогоне `getMccString()`
вернул `null`.

Пришедший ему на смену `staleKeys()` (сверка фактических значений) как единственное
условие записи тоже оказался неполон: он отвечает на вопрос «действует ли конфиг», а нужен
ответ на «лежит ли persistent-копия на диске». В момент чистки кэша эти ответы расходятся —
файла уже нет, значения ещё на месте, `staleKeys()` пуст, запись пропускается, и пропажа
обнаруживается только ребутом.

Стало — два независимых повода, эвристик нет:

1. `staleKeys()` не пуст — значения перетёрли;
2. persistent-копии нет на диске.

Второй повод проверяется прямо: привилегированный процесс дёргает `dump()` у сервиса
`carrier_config` и ищет в секции `Cached config files` строку с нужным carrierId и
префиксом ICCID. Каталог `com.android.phone` напрямую не прочесть (нужен его uid, а
делегирование даёт только permissions), зато в шелловском наборе есть
`android.permission.DUMP` — ровно то, что `CarrierConfigLoader.dump()` и проверяет.

Безусловная запись «на всякий случай» не годится: запись **действительно** возвращается
к нам новым `CARRIER_CONFIG_CHANGED` — не напрямую (`overrideConfig` заканчивается на
`updateSubscriptionDatabase()`), а через `siminfo`. Замерено на живом устройстве:
128 прогонов за 7 секунд. Теперь прогон, которому нечего делать, не пишет и потому не
порождает нового бродкаста — цепочка обрывается сама:

```
provider: run 1
cached config files: 6
subId=5 (25001) up to date, 27 keys checked
subId=9 (20601) up to date, 27 keys checked
```

`RunGuard` остался, но только как глушилка и предохранитель, не как условие записи:

```
DEBOUNCE_MS = 5000    гасит пачку бродкастов
QUIET_MS    = 60_000  тишина, после которой предохранитель взводится заново
MAX_RUNS    = 20      предохранитель, срабатывать не должен
```

Гейт спрашивается только когда Shizuku реально доступен — иначе пачки бродкастов при
лежащем Shizuku съедали бы и debounce, и лимит впустую.

**Debounce откладывает, а не выбрасывает.** На попадание в окно `claim()` отвечает
остатком окна, и ресивер, не отпуская `goAsync()`, один раз повторяет запрос по его
истечении (`receiver retry` в логе). Прогон стартует по первому бродкасту пачки
(`simState=UNKNOWN`, без subId), а полезные — с subId и загруженными records — приходят
на 0.8–1.4 с позже. Раньше их съедал debounce, и SIM, не успевшая загрузиться к прогону,
оставалась с чужим RAM-слоем до следующего случайного бродкаста: так Cyta после MTS
показывала «CYTA — Mts». Цена — лишний пустой прогон на пачку. У провайдера повтора нет:
его прогоны идут от перезапуска процесса, а не от событий SIM.

#### Откуда берётся ICCID

Прямые API его не отдают, хотя права делегированы: `SubscriptionInfo.getIccId()` возвращает
пустую строку, `TelephonyManager.getSimSerialNumber()` кидает
`SecurityException: getIccSerialNumber: The uid 20448 does not meet the requirements to
access device identifiers` — редактирование идентификаторов смотрит на пакет вызывающего,
а он у нас sdk-sandbox. Берём из дампа `isub`: там ICCID урезан до 9 символов, а для сверки
с именем файла нужно 5 (`getFilePathForLogging()` оставляет первые 5 и дописывает
фиксированную звёздочную строку).

Если ICCID не достался, матч вырождается в счётный: файлов с нужным carrierId должно быть
не меньше, чем активных SIM с таким же carrierId.

**Остаточная неточность.** Пять символов ICCID — это префикс эмитента, у всех SIM одного
оператора он общий. Поэтому файл от вынутой SIM того же оператора сойдёт за наш. Случай
узкий: он требует, чтобы `staleKeys()` при этом был пуст, то есть чтобы в слоте лежал
override от прежней симки **того же** оператора (см. «Оверрайд живёт в слоте, а не в
симке»). При обычной чистке кэша вопрос не встаёт — `clearCachedConfigForPackage` сносит
все файлы разом, частичных состояний не бывает.

SIM с нечитаемыми MCC/MNC пропускается до следующего прогона — лучше отложить, чем записать
конфиг без оператор-специфичной части.

## Диагностика

```bash
adb shell dumpsys carrier_config          # mPersistentOverrideConfigs / mOverrideConfigs
                                          # + "Cached config files" + лог событий
adb shell logcat -s vvb                   # run N / debounced / cached config files: N
                                          # applying … stale=[…] | persistent copy missing
adb shell logcat -G 16M                   # буфер по умолчанию 256 KiB, боевые события вытесняются
adb shell dumpsys telecom                 # PROPERTY_CHANGE: xsim | wifi — каким путём пошёл звонок
adb shell dumpsys telephony.registry      # ServiceState, IMS PDN, notifyDataConnectionForSubscriber
adb shell am broadcast -a io.github.vvb2060.ims.action.APPLY     -n io.github.vvb2060.ims/.ConfigChangeReceiver     # ручной прогон
```

`cached config files: dump failed` в логе означает, что проверка наличия файла не
отработала и решение осталось за `staleKeys()`.

`privileged: no transact from provider …` — sandbox не дождался биндера от провайдера
(см. «GET_BINDER и SEND_BINDER гоняются») и завершился впустую. Висящий
`io.github.vvb2060.ims_sdk_sandbox_instr` в `ps -A` — то же самое без таймаута.

**Первым делом смотреть на файлы, а не на слои в памяти:**

```bash
adb shell dumpsys carrier_config | sed -n '/Cached config files/,$p' | grep override
```

Должно быть по строке `carrierconfig-…-override-<ICCID>-<carrierId>.xml` на активную SIM.
Нет строки — persistent-копии нет, и до ближайшего ребута это больше нигде не видно:
`mPersistentOverrideConfigs` в дампе остаётся полным, потому что чистилка кэша трогает
только файлы. Список печатается фильтром `startsWith("carrierconfig-")`, то есть без
изъятий; ICCID в именах маскируется, первые 5 цифр видны.

Секция `CarrierConfigLoader local log` — кольцевой буфер, живёт от загрузки; строки
`Notified carrier config changed`, `Package changed:` и `Build fingerprint changed`
как раз и означают, что кэш почистили.

Маркеры в `dumpsys telecom` по звонку:

- `Added [[ xsim]]` — cross-SIM (Backup Calling)
- `Added [[ wifi]]` — VoWiFi
- нет свойств + `ImsReasonInfo: null` — звонок вообще не IMS (CS)
- инициатор `REQUEST_DISCONNECT` в скобках: `cgad` = com.google.android.dialer (пользователь)

`cmd phone cc` из shell не работает: `secureOverrideConfig()` режет вызовы по uid —
`isShell(getCallingUid())` → `SecurityException("overrideConfig cannot be invoked by shell")`.

Разбирать саму реализацию удобнее из прошивки, а не по исходникам AOSP — они расходятся:

```bash
adb pull /system/priv-app/TeleService/TeleService.apk
unzip -o TeleService.apk classes.dex -d dex
"$ANDROID_HOME/build-tools/36.1.0/dexdump" -d dex/classes.dex > dd.txt   # ~730k строк
```

## Сборка

- **Дистрибутив Gradle не качается**: `services.gradle.org/distributions/*.zip` отдаёт 307 на
  заблокированный CDN. Качать с зеркала (`mirrors.cloud.tencent.com/gradle/`), сверять с
  официальным `.sha256` (он отдаётся напрямую), класть в
  `%GRADLE_USER_HOME%\wrapper\dists\gradle-<ver>-bin\<hash>\`.
- **JDK 21 тоже не качается** (GitHub releases). Собирать на JBR от Android Studio:
  ```bash
  JAVA_HOME="D:/Program Files/Android Studio/jbr" ./gradlew :app:assembleRelease
  ```
- `GRADLE_USER_HOME` = `D:\ProgramData\AndroidStudioSDK\.gradle` (машинная переменная). В
  `C:\temp` лежит только то, что не жалко потерять; всё, что тянется по сети (дистрибутивы
  Gradle в `wrapper\dists`, зависимости в `caches\modules-2`), живёт на D.
- **proguard вырезает `Log.d` и `Log.v`** в release (`-assumenosideeffects`). Диагностику
  писать через `Log.i`.

## Известная нерешённая проблема

После перезагрузки cross-SIM (Backup Calling) не поднимается сам: ePDG-туннель встаёт
(`transport: WLAN` в PDN-логе), но IMS-регистрация не проходит, и звонки идут через CS.

Установлено:

- к carrier config отношения не имеет — все ключи на месте сразу после ребута;
- к приложению отношения не имеет — чинилось при лежащем Shizuku, без единого запуска;
- `cmd phone ims disable/enable -s 0` **не помогает** — перезапуска IMS-стека мало;
- помогает включение Wi-Fi (регистрация по VoWiFi, затем хендовер на cross-SIM при пропаже
  Wi-Fi, ~30 с) и передёргивание авиарежима.

Рабочая гипотеза: при загрузке carrier config восстанавливается несколькими волнами, а IMS
поднимается прямо посреди этого, регистрация срывается и не повторяется.
