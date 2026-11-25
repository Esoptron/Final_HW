# Стенд производительности Currency Rates

В проекте подключены Spring Boot Actuator, Micrometer с экспортом для Prometheus и OpenTelemetry с экспортом в Jaeger. JMeter выполняет полный пользовательский цикл сбора курсов. Профиль JMH сравнивает XML-парсер и способы обработки полей.

- Actuator слушает `127.0.0.1:8081`. Доступны `health`, `info`, `metrics`, `prometheus` и `threaddump`.
- Профиль `fixture` запускает локальный HTTP-источник с XML из 43 валют на порту 8090. Бизнес-операция сохраняет пять курсов.
- Профиль `trace` посылает данные в Jaeger через OTLP HTTP и добавляет время этапов в лог. Его можно включить одновременно с `fixture`.
- Базовый коммит для сравнения - [`ca1f12f`](https://github.com/Esoptron/Final_HW/commit/ca1f12f). Сценарий JMeter и дашборд находятся в этой ветке.

## Запуск и повторение замеров

Команды ниже рассчитаны на PowerShell и запускаются отдельно в папке проекта. Бинарники JMeter, Prometheus, Grafana, VisualVM и Jaeger указываются полным путём или добавляются в PATH.

```powershell
.\mvnw.cmd clean verify
java -jar target/currency-rates-1.0.0.jar --spring.profiles.active=fixture
```

В другом терминале запускается приложение. Путь базы для каждого замера должен быть новым.

```powershell
java -Xms128m -Xmx128m -XX:+UseG1GC '-Xlog:gc*:file=gc-perf.log:time,uptime,level,tags' -jar target/currency-rates-1.0.0.jar --collector.enabled=false --rates.fallback-enabled=false --rates.cbr-url=http://127.0.0.1:8090/fixture/cbr.xml --management.server.port=8082 '--spring.datasource.url=jdbc:h2:file:./data/perf-after;DB_CLOSE_ON_EXIT=FALSE'
jmeter.bat -n -t performance/currency-rates.jmx -Jusers=12 -Jseconds=15 -l warmup.jtl
jmeter.bat -n -t performance/currency-rates.jmx -Jusers=12 -Jseconds=35 -l samples.jtl -e -o jmeter-html
```

Для базовой версии соберите коммит `ca1f12f` в отдельной рабочей папке, затем повторите запуск с портом Actuator 8081 и другим путём H2. Сценарий JMeter берётся из ветки с итоговой работой. Для Grafana подключите источник Prometheus `http://127.0.0.1:9090` с UID `prometheus` и импортируйте [JSON дашборда](grafana/dashboards/currency-rates.json). Для автоматического подключения можно задать `GF_PATHS_PROVISIONING` на каталог `performance/grafana/provisioning`, а `GF_CURRENCY_DASHBOARDS` - на абсолютный путь к `performance/grafana/dashboards`.

```powershell
prometheus.exe --config.file=performance/prometheus.yml --web.listen-address=127.0.0.1:9090
Invoke-RestMethod http://127.0.0.1:8082/actuator/health
Invoke-RestMethod 'http://127.0.0.1:8082/actuator/metrics/http.server.requests?tag=uri:/answer'
Invoke-RestMethod 'http://127.0.0.1:8082/actuator/metrics/currency.parse.duration?tag=outcome:success'
Invoke-WebRequest http://127.0.0.1:8082/actuator/threaddump -OutFile threads.json
```

JFR, текстовый дамп и снимок кучи можно получить средствами JDK. Подставьте PID процесса из `jcmd -l`. Снимайте heap dump после JMeter, поскольку эта операция влияет на нагрузку и может вызвать Full GC.

```powershell
jcmd -l
jcmd <PID> JFR.start name=currency settings=profile duration=30s filename=currency.jfr
jcmd <PID> Thread.print -l > threads.txt
jcmd <PID> GC.heap_dump heap.hprof
```

Для Jaeger запустите `jaeger-all-in-one.exe` с включённым приёмом OTLP на 4318. Стартуйте стендовый источник с `--spring.profiles.active=fixture,trace`, приложение - с `--spring.profiles.active=trace`. Отправьте запрос на сбор и откройте `http://127.0.0.1:16686`. Пример команды запуска и путь к настройкам приложения доступны в документации. [Jaeger 1.76](https://www.jaegertracing.io/docs/1.76/getting-started/) · [Spring Boot 3.5 Tracing](https://docs.spring.io/spring-boot/3.5/reference/actuator/tracing.html)

```powershell
.\mvnw.cmd -Pjmh test-compile dependency:build-classpath '-Dmdep.outputFile=target/jmh-classpath.txt'
$cp = "target/test-classes;target/classes;" + (Get-Content target/jmh-classpath.txt -Raw).Trim()
java -cp $cp org.openjdk.jmh.Main '.*CurrencyParsingBenchmark.*' -f 2 -wi 2 -i 3 -w 1s -r 1s -t 1 -prof gc -jvmArgs '-Xms128m -Xmx128m -XX:ActiveProcessorCount=4' -rf json -rff jmh.json
```

Для повторения полного теста на базовой версии распакуйте из базового JAR каталог `BOOT-INF/classes` и поставьте его первым в `$cp`. Выберите фильтр `'.*CurrencyParsingBenchmark.fullXml'`. Так JMH использует тот же тест и фактический парсер базового коммита.
