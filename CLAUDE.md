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

### Почему сверка по ключам, а не по маркеру версии

Было: `bundle.getInt("vvb2060_config_version") != BuildConfig.VERSION_CODE` → пропустить.
Проблемы:

1. Правки в `getConfig()` без бампа `versionCode` не применялись никогда.
2. Маркер читается из **склеенного** конфига и не замечает чужих перезаписей.
3. `GetMts()` под тем же guard'ом: если при первом прогоне `getMccString()` вернул `null`,
   писался базовый конфиг с маркером, и MTS-часть не применялась уже никогда.

Стало: `staleKeys()` сверяет фактические значения всех ключей. Обязательно, а не «удобнее»:
собственная запись конфига рассылает `CARRIER_CONFIG_CHANGED`, ресивер срабатывает повторно,
и с прежним guard'ом это был бы бесконечный цикл.

SIM с нечитаемыми MCC/MNC пропускается до следующего прогона — лучше отложить, чем записать
конфиг без оператор-специфичной части.

## Диагностика

```bash
adb shell dumpsys carrier_config          # mPersistentOverrideConfigs / mOverrideConfigs
                                          # + "Cached config files" + лог событий
adb shell logcat -s vvb                   # applying stale=[...] / up to date, N keys checked
adb shell logcat -G 16M                   # буфер по умолчанию 256 KiB, боевые события вытесняются
adb shell dumpsys telecom                 # PROPERTY_CHANGE: xsim | wifi — каким путём пошёл звонок
adb shell dumpsys telephony.registry      # ServiceState, IMS PDN, notifyDataConnectionForSubscriber
```

Маркеры в `dumpsys telecom` по звонку:

- `Added [[ xsim]]` — cross-SIM (Backup Calling)
- `Added [[ wifi]]` — VoWiFi
- нет свойств + `ImsReasonInfo: null` — звонок вообще не IMS (CS)
- инициатор `REQUEST_DISCONNECT` в скобках: `cgad` = com.google.android.dialer (пользователь)

`cmd phone cc get-value` из shell не работает — Permission denied, нужны привилегии.

## Сборка

- **Дистрибутив Gradle не качается**: `services.gradle.org/distributions/*.zip` отдаёт 307 на
  заблокированный CDN. Качать с зеркала (`mirrors.cloud.tencent.com/gradle/`), сверять с
  официальным `.sha256` (он отдаётся напрямую), класть в
  `%GRADLE_USER_HOME%\wrapper\dists\gradle-<ver>-bin\<hash>\`.
- **JDK 21 тоже не качается** (GitHub releases). Собирать на JBR от Android Studio:
  ```bash
  JAVA_HOME="D:/Program Files/Android Studio/jbr" ./gradlew :app:assembleRelease
  ```
- `GRADLE_USER_HOME` = `C:\temp\gradle` — чистка temp сносит кэш целиком.
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
