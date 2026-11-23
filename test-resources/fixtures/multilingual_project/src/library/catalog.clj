(ns library.catalog
  "Каталог фонда библиотеки: книги, их экземпляры и спрос на них.")

(defn find-by-author
  "Ищет в каталоге все книги указанного автора без учёта регистра букв в
  его фамилии.

  Catalog -> String -> [Book]"
  [catalog author]
  (filter #(= (.toLowerCase ^String author) (.toLowerCase ^String (:author %))) catalog))

(defn add-copy
  "Регистрирует в фонде новый экземпляр книги с инвентарным номером, чтобы
  его можно было выдавать.

  Catalog -> Isbn -> CopyId -> Catalog"
  [catalog isbn copy-id]
  (update-in catalog [isbn :copies] (fnil conj #{}) copy-id))

(defn popular-titles
  "Возвращает книги, которые чаще всего брали за период, по убыванию
  числа выдач.

  [LoanEvent] -> Long -> [Isbn]"
  [events n]
  (->> (frequencies (map :isbn events))
       (sort-by (comp - val))
       (take n)
       (map key)))

(defn find-by-title
  "Ищет книги, в названии которых встречается заданное слово.

  Catalog -> String -> [Book]"
  [catalog word]
  (filter #(.contains ^String (:title %) word) catalog))

(defn find-by-isbn
  "Карточка книги по её международному стандартному номеру.

  Catalog -> Isbn -> Maybe Book"
  [catalog isbn]
  (get catalog isbn))

(defn write-off-copy
  "Списывает из фонда ветхий или утерянный экземпляр, после чего его нельзя
  выдать.

  Catalog -> Isbn -> CopyId -> Catalog"
  [catalog isbn copy-id]
  (update-in catalog [isbn :copies] disj copy-id))

(defn top-authors
  "Авторы, чьи книги читают больше всего, по убыванию общего числа выдач
  их произведений.

  [LoanEvent] -> Catalog -> Long -> [String]"
  [events catalog n]
  (->> events
       (map #(:author (get catalog (:isbn %))))
       frequencies
       (sort-by (comp - val))
       (take n)
       (map key)))

(defn import-catalog
  "Загружает описания книг из файла поставщика и добавляет их в каталог.

  Catalog -> String -> IO Catalog"
  [catalog path]
  (into catalog (read-string (slurp path))))

(defn transfer-copy
  "Передаёт экземпляр книги из одного филиала библиотеки в другой.

  Catalog -> CopyId -> BranchId -> Catalog"
  [catalog copy-id branch-id]
  (assoc-in catalog [:locations copy-id] branch-id))

(defn available-copies
  "Экземпляры книги, которые сейчас стоят на полке и могут быть выданы.

  Catalog -> Loans -> Isbn -> #{CopyId}"
  [catalog loans isbn]
  (set (remove (partial contains? loans) (get-in catalog [isbn :copies]))))
