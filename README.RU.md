# MCP Clojure Code Index

MCP-сервер, который позволяет LLM-агенту получать точные и компактные ответы на вопросы о кодовой базе на Clojure: где определено то или иное имя, кто его использует, что содержит namespace, каков исходный код конкретного определения (Definition), какие существуют реализации (Implementations) протокола или мультиметода и, опционально, где находится код, выполняющий определённое действие.

## Назначение

LLM-агенты тратят большую часть своих токенов на изучение кода: сначала выполняют `grep`, а затем читают файлы, чтобы разобраться, что именно представляет собой каждое найденное совпадение.

Этот сервер отвечает на вопросы вроде «где определён X», «кто использует X», «что содержится в этом namespace» и «где находится код, выполняющий Y», предоставляя **компактные ответы** (одна строка на каждую найденную сущность, с жёстким ограничением размера), которые при этом **полнее результатов `grep`**.

Сервер находит использования через любые alias, `:refer`, `#'var`, `with-redefs`, функции, переданные в качестве значений, а также keywords в исходном коде и EDN-файлах.

Если ответ когда-либо оказывается объёмнее или менее точным, чем результат `grep`, это считается ошибкой.

Принципы проектирования описаны в `docs/adr/`, терминология — в `GLOSSARY.md`.

## Возможности

- **Определения на основе анализа clj-kondo** — vars, macros, тесты (`deftest`), multimethods и их методы, specs (`s/def`), protocols, records, а также формы проектных макросов, объявленных через `lint-as` в `.clj-kondo/config.edn` проекта.
- **Поиск использований полнее, чем `grep`** — aliases, `:refer`, `#'var`, функции, переданные в качестве значений, `with-redefs`, формы `comment`, `:require`, keywords в исходном коде и в файлах `.edn`.
- **Ограничение объёма вывода (Output budget)** — каждый ответ содержит одну строку на найденную сущность, с жёсткими ограничениями по количеству строк и символов; поддерживается постраничная выдача с помощью `offset`.
- **Актуальность данных (Freshness)** — файлы, изменённые на диске, анализируются повторно перед каждым ответом; индексация embeddings догоняет изменения в фоновом режиме.
- **Qdrant и Ollama необязательны** — они нужны только для `semantic_search`; все остальные инструменты работают без них.

## Требования

- [Java](https://adoptium.net/) 21+
- [Leiningen](https://leiningen.org/) 2.11+ (только для разработки и сборки)
- Только для `semantic_search` (опционально):
  - [Qdrant](https://qdrant.tech/) — векторная база данных.
  - [Ollama](https://ollama.com/) с моделью `vishalraj/nomic-embed-code` — для создания code embeddings.
  - Опционально reranker: `llama.cpp` с командой `llama-server --rerank` и моделью `qllama/bce-reranker-base_v1` — точно сортирует найденные результаты и присваивает им оценку релевантности.

Если Qdrant или Ollama недоступны, сервер всё равно запускается, и все инструменты точного поиска продолжают работать. При этом `semantic_search` сообщает, что он недоступен.

Без reranker функция `semantic_search` также работает: результаты сортируются на основе объединения векторного поиска и поиска по словам в именах и docstrings. При этом оценка релевантности не возвращается.

## Установка и запуск

### 1. Установка и запуск необязательных зависимостей (только для семантического поиска)

<details> <summary><b>Qdrant</b> — векторная база данных</summary>

```bash
docker run -d -p 6333:6333 --name qdrant qdrant/qdrant
```

Проверка:

```bash
curl http://localhost:6333/collections
```

</details>

<details> <summary><b>Ollama</b> — модель для создания embeddings</summary>

```bash
ollama pull vishalraj/nomic-embed-code
```

</details>

<details> <summary><b>Reranker</b> (опционально) — llama-server с моделью BCE</summary>

Ollama не может самостоятельно запускать модель reranking (у неё нет API для reranking), однако может скачать эту модель. В Linux-сборки Ollama входит `llama-server`, который можно использовать следующим образом:

Bash:

```bash
ollama pull qllama/bce-reranker-base_v1
MODEL=$(ollama show --modelfile qllama/bce-reranker-base_v1 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Fish:

```
ollama pull qllama/bce-reranker-base_v1
set MODEL (ollama show --modelfile qllama/bce-reranker-base_v1 | sed -n 's/^FROM //p')
LD_LIBRARY_PATH=/usr/local/lib/ollama /usr/local/lib/ollama/llama-server -m "$MODEL" --rerank --port 8012
```

Любой `llama-server` из проекта [llama.cpp](https://github.com/ggml-org/llama.cpp) работает аналогичным образом.

Проверка:

```bash
curl http://localhost:8012/health
```

</details>

### 2. Настройка

`resources/config.edn` содержит конфигурацию по умолчанию.

Корневая директория проекта определяется следующим образом (в порядке приоритета):

1. Значение `:index/root-path`.
2. Переменная окружения `QD_PROJECT_ROOT` или `VSCODE_CWD`.
3. Текущая рабочая директория.

| Ключ                           | Значение по умолчанию                                                  | Описание                                                                                                                                                                                 |
| ------------------------------ | ---------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `:index/root-path`             | см. выше                                                               | Путь к индексируемому проекту                                                                                                                                                            |
| `:index/include-extensions`    | `[".clj" ".cljc" ".cljs" ".edn"]`                                      | Расширения файлов, подлежащих анализу                                                                                                                                                    |
| `:index/exclude`               | `[]`                                                                   | Директории, исключаемые из анализа. Директории `.calva`, `.claude`, `.clj-kondo`, `.cpcache`, `.git`, `.kilo`, `.lsp`, `.mcp`, `logs`, `target`, `tmp` всегда исключаются                |
| `:output/limit`                | `50`                                                                   | Бюджет вывода: максимальное количество найденных сущностей в одном ответе. Параметр `limit` отдельного вызова может только уменьшить это значение                                        |
| `:output/max-chars`            | `4000`                                                                 | Бюджет вывода: максимальное количество символов в одном ответе                                                                                                                           |
| `:doc/signature`              | `{:strategy :last-paragraph}`                                          | Как найти сигнатуру типов в docstring: `:last-paragraph` (последний абзац docstring из двух и более абзацев) или `{:strategy :pattern :regex "..."}` (первая группа регулярного выражения); `nil` отключает сигнатуры |
| `:qdrant/host`, `:qdrant/port` | `"localhost"`, `6333`                                                  | Адрес Qdrant (только для семантического поиска)                                                                                                                                          |
| `:qdrant/collection`           | Переменная окружения `QD_COLLECTION`, иначе `<project dir>-collection` | Имя коллекции Qdrant (только для семантического поиска)                                                                                                                                  |
| `:qdrant/recreate?`            | `false`                                                                | Удалить и заново создать коллекцию при запуске (только для семантического поиска)                                                                                                        |
| `:ollama/url`                  | `"http://localhost:11434/v1"`                                          | Адрес API Ollama (только для семантического поиска)                                                                                                                                      |
| `:ollama/embedding-model`      | `"vishalraj/nomic-embed-code"`                                         | Модель для создания embeddings (только для семантического поиска)                                                                                                                        |
| `:reranker/url`                | `"http://localhost:8012/v1"`                                           | Адрес reranker — API `/v1/rerank`, например `llama-server --rerank` (только для семантического поиска, опционально)                                                                      |
| `:reranker/model`              | `"qllama/bce-reranker-base_v1"`                                        | Имя модели, передаваемое reranker (только для семантического поиска)                                                                                                                     |
| `:search/top-k`                | `100`                                                                  | Количество кандидатов, получаемых отдельно из векторного поиска и поиска по словам, а также количество объединённых кандидатов, передаваемых reranker (только для семантического поиска) |
| `:search/re-rank-top`          | `10`                                                                   | Количество результатов, оставляемых после reranking (только для семантического поиска)                                                                                                   |
| `:embedding/cache-dir`         | `<root>/.mcp/embedding-cache`                                          | Директория кэша embeddings (только для семантического поиска)                                                                                                                            |

Если ответ достигает установленного Output budget, в его конце добавляется уведомление об усечении. Например:

`truncated: shown 50 of 120, pass offset=50 or narrow the query`

Это означает, что показано 50 результатов из 120. Чтобы получить следующие результаты, нужно передать `offset=50` либо сузить запрос.

### 3. Сборка uberjar

Перед первым запуском или после изменения исходного кода необходимо собрать исполняемый JAR:

```bash
lein uberjar
```

### 4. Запуск сервера

```bash
java -jar target/mcp-0.1.0-SNAPSHOT-standalone.jar resources/config.edn
```

Сервер запускается в режиме STDIO и ожидает сообщения JSON-RPC 2.0.

При запуске он анализирует проект с помощью clj-kondo (результаты хранятся только в оперативной памяти) и в фоновом режиме вычисляет embeddings для Search units.

При последующих запусках embeddings пересоздаются только для файлов, изменившихся с момента их последней индексации.

Коллекция Qdrant старого формата удаляется и создаётся заново.

## Интеграция с Kilo Code

### Глобальная конфигурация (доступна из любого проекта)

Сервер запускается через uberjar, а не через `lein run`.

Это необходимо потому, что Kilo использует таймаут 30 секунд, тогда как `lein run` требует более 60 секунд на запуск JVM и Leiningen.

Чтобы избежать проблем с `PATH` при запуске из Kilo, для `command`, JAR-файла и `config.edn` необходимо использовать абсолютные пути.

Добавьте следующую конфигурацию в `~/.config/kilo/kilo.jsonc`:

```json
{
  "mcp": {
    "clojure-code-index": {
      "type": "local",
      "command": ["/usr/bin/java", "-jar", "/path/to/mcp/target/mcp-0.1.0-SNAPSHOT-standalone.jar", "/path/to/mcp/resources/config.edn"],
      "workingDirectory": "/path/to/mcp",
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

Замените `/path/to/mcp` полным путём к локальной копии этого проекта, а `/usr/bin/java` — путём к используемому JDK.

### Локальная конфигурация (для отдельного проекта)

Создайте файл `kilo.json` в корневой директории проекта либо `.kilo/kilo.jsonc`:

```json
{
  "mcp": {
    "clojure-code-index": {
      "type": "local",
      "command": ["/usr/bin/java", "-jar", "/path/to/mcp/target/mcp-0.1.0-SNAPSHOT-standalone.jar", "/path/to/mcp/resources/config.edn"],
      "enabled": true,
      "timeout": 30000
    }
  }
}
```

### Проверка

После настройки перезапустите Kilo Code.

В интерфейсе появится MCP-сервер `clojure-code-index`.

После его включения Kilo автоматически обнаружит следующие 6 инструментов:

| Инструмент             | Описание                                                                                           |
| ---------------------- | -------------------------------------------------------------------------------------------------- |
| `find_definition`      | Поиск места, где определено имя                                                                    |
| `find_usages`          | Поиск всех использований одного или нескольких имён и/или keywords                                 |
| `namespace_summary`    | Сводная информация об определениях namespace и о том, какие namespace подключают его через require |
| `get_source`           | Получение исходного кода конкретного определения                                                   |
| `find_implementations` | Поиск реализаций протокола или мультиметода                                                        |
| `semantic_search`      | Поиск определений и записей EDN по смыслу (требуются Qdrant и Ollama)                              |

## Доступные MCP-инструменты

Это именно те инструменты, которые возвращаются методом `tools/list`.

Имена могут быть указаны в следующих форматах:

- Простое имя: `relative-path`.
- С указанием alias, как он записан в соответствующем namespace: `output/relative-path`.
- Полностью квалифицированное имя: `mcp.output/relative-path`.

Если имя неоднозначно, сервер возвращает список подходящих кандидатов.

Все инструменты, кроме `get_source`, поддерживают параметры:

- `limit` — ограничение количества результатов (может только уменьшать значение `:output/limit`).
- `offset` — количество пропускаемых результатов (для постраничной выдачи).

Все ответы соблюдают ограничения Output budget. Docstring summary в строках ответа — первое предложение docstring, за которым следует `(+N chars)`, если текст продолжается. Перед каждым ответом индекс перечитывается из изменённых файлов (по mtime), поэтому правки видны сразу.

### find_definition

```text
Входные параметры:
  name (string, обязательный) — простое, квалифицированное через alias
                               или полностью квалифицированное имя;
                               `multi dispatch-value` для defmethod
                               (например, `area :circle`);
                               keyword для spec.
  limit, offset

Ответ:
  Одна строка на каждое определение (Definition):
  path:line  kind  ns/name  [arglists]  Docstring summary
```

Пример:

```text
src/mcp/output.clj:25  defn  mcp.output/relative-path  [root-path file-path]  Return ...
```

### find_usages

```text
Входные параметры:
  targets (array of strings, обязательный) — имена и/или keywords
                                             (:k, :ns/k, ::k, ::alias/k)
  kinds (array of strings) — только следующие типы использований (Usage):
      call, value, var-ref, redef, in-comment, require, keyword, edn
      По умолчанию: все типы.
  include_tests (boolean, по умолчанию true)
  context (boolean, по умолчанию true) — добавлять к строке контекст (Usage context)
  warnings_only (boolean, по умолчанию false) — только использования с предупреждением
                                                (Usage warning)
  limit, offset

Ответ:
  Для каждой цели — заголовок `target: N usages`,
  затем одна строка на каждое использование (Usage):
  path:line:col  usage-kind  ns/enclosing-definition  context  warning
```

Контекст (Usage context) — до 80 символов строки исходника вокруг использования, серии пробелов схлопнуты; `…` отмечает обрезанный конец.

Предупреждение (Usage warning) завершает строку, если оно есть: `wrong arity: 3 args, takes 1|2` (`takes 2+` для varargs) — у вызова число аргументов не совпадает ни с одной арностью цели; у `require` целевого namespace — `unused`, если в файле не используется ни алиас, ни одно из имён `:refer` (вызов или значение через алиас, имя из `:refer`, `#'`, `::alias/kw`, полностью квалифицированное имя), и `unused refer: b, c`, если не используются только эти имена из `:refer`. Голый require и `:refer :all` не помечаются. `unused` — подсказка: использование внутри макроса или через `resolve` не видно.

Если ничего не найдено, сервер сообщает, что именно было проверено. Например:

`no usages of x (checked 214 files, including tests)`

Это означает, что использований `x` не найдено (проверено 214 файлов, включая тесты).

### namespace_summary

```text
Входные параметры:
  ns (string, обязательный) — полностью квалифицированное имя namespace
  limit, offset

Ответ:
  Заголовок:
      ns name  path  Docstring summary

  Для каждого Definition:
      line  public|private  kind  name  [arglists]  Docstring summary

  Последняя строка:
      required by: ns1, ns2, ...
      либо `none`, если namespace нигде не подключается
```

### get_source

```text
Входные параметры:
  name (string, обязательный) — простое, квалифицированное через alias
                                или полностью квалифицированное имя.
  limit, offset — применяются к строкам тела определения
                  (или к списку кандидатов, если имя неоднозначно).

Ответ:
  Заголовок вида `path:start-end`,
  затем исходный код ровно одного Definition.

  Если тело определения превышает Output budget,
  в конце добавляется уведомление об усечении.
  Чтобы получить оставшуюся часть, передайте offset.
```

### find_implementations

```text
Входные параметры:
  name (string, обязательный) — протокол или мультиметод
  limit, offset

Ответ:
  Одна строка на каждую реализацию (Implementation):
  defrecord, deftype, extend-type, extend-protocol, reify, defmethod

  path:line  ns/type-or-dispatch-value  method
```

### semantic_search

```text
Входные параметры:
  query (string, обязательный) — описание того, что делает код,
                                 на естественном языке.
  include_tests (boolean, по умолчанию false) — искать также
                                 определения в Test paths.
  limit, offset

Поиск:
  embedding запроса → Qdrant top :search/top-k ─┐
  слова из имён и docstrings (BM25) ────────────┴→
  объединение рангов (reciprocal rank fusion) →
  reranker → top :search/re-rank-top

Ответ:
  Одна строка на каждый результат.
  Исходные тела функций не возвращаются.

  score  path:line  kind  ns/name  Docstring summary

  score — оценка релевантности от reranker в диапазоне 0..1.
          Не возвращается, если reranker недоступен.

  Пока выполняется индексация последних изменений,
  в конце ответа добавляется:
  `embeddings stale for N files`
```

Требуются Qdrant и Ollama. Если они недоступны, инструмент сообщает, что семантический поиск недоступен.

## Архитектура

```text
src/mcp/
├── server.clj         — точка входа, JSON-RPC через STDIO,
│                        проверка актуальности данных перед каждым вызовом инструмента
├── config.clj         — загрузка конфигурации
├── code_index.clj     — анализ проекта с помощью clj-kondo
│                        в оперативной памяти (Definitions, Usages)
├── usages.clj         — Usages и их типы
├── output.clj         — Output budget и вспомогательные функции
│                        для формирования компактных ответов
├── tool.clj           — реестр инструментов (multimethods spec/call)
├── tools.clj          — tools/list и tools/call
├── tools/             — отдельный namespace для каждого инструмента
├── search_units.clj   — Search units для семантического поиска
├── semantic_index.clj — фоновое создание embeddings для Search units
├── embeddings.clj     — embeddings через Ollama,
│                        reranking через сервер с API /v1/rerank
├── lexical.clj        — поиск по словам (BM25) в именах Search units и docstrings
├── qdrant.clj         — векторное хранилище (Qdrant HTTP API)
└── search.clj         — векторный поиск + поиск по словам,
                         объединение рангов и reranking
```

## Разработка

```bash
# Тесты (Qdrant и Ollama не требуются)
lein test

# Интеграционные тесты, которым нужны Qdrant и Ollama
# (проходят как с reranker, так и без него)
lein test :integration

# Статический анализ
clj-kondo --lint src test

# Сборка uberjar (после изменения исходного кода)
lein uberjar
```
