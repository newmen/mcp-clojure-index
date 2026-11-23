(ns app.signatures
  "Type signature fixtures.

  Namespaces have no Type signature, so this paragraph is not one.")

(def page-size
  "Rows per page.

  Long"
  20)

(defn relative-path
  "Путь к файлу относительно корня.

  String -> String"
  [file-path]
  file-path)
