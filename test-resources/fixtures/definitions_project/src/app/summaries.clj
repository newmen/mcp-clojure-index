(ns app.summaries
  "Docstring summary fixtures.")

(defn relative-path
  "Возвращает путь к файлу относительно корня
   проекта, если файл лежит внутри него. Иначе путь
   возвращается без изменений.

  Maybe String -> String -> String"
  [root file-path]
  [root file-path])

(def limit
  "Lines per page."
  10)

(defn cache!
  "Stores x, e.g. `foo!` in the atom of the cache.

  Any -> IO ()"
  [x]
  x)

(defn clear!
  "Calls `cache!` and `set-cached-tc!` with nil.

  IO ()"
  []
  (cache! nil))
