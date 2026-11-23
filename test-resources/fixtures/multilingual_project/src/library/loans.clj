(ns library.loans
  "Выдача книг читателям, их возврат и штрафы за просрочку.")

(defn issue-book
  "Выдаёт читателю экземпляр книги и записывает дату, к которой его нужно
  вернуть: через две недели от сегодняшнего дня.

  Loans -> ReaderId -> CopyId -> LocalDate -> Loans"
  [loans reader-id copy-id today]
  (assoc loans copy-id {:reader reader-id :due (.plusDays today 14)}))

(defn return-book
  "Принимает экземпляр обратно в библиотеку и закрывает запись о выдаче,
  после чего книгу снова можно выдавать.

  Loans -> CopyId -> Loans"
  [loans copy-id]
  (dissoc loans copy-id))

(defn overdue-fine
  "Считает, сколько читатель должен заплатить за просрочку: дневная ставка
  умножается на число дней сверх срока возврата.

  Loan -> LocalDate -> Double -> Double"
  [loan today rate]
  (* rate (max 0 (- (.toEpochDay today) (.toEpochDay (:due loan))))))

(defn extend-loan
  "Продлевает срок возврата на заданное число дней, если эту книгу не ждёт
  в очереди другой читатель.

  Loan -> Long -> Boolean -> Loan"
  [loan days reserved?]
  (cond-> loan
    (not reserved?) (update :due #(.plusDays % days))))

(defn due-date
  "Дата, к которой читатель обязан вернуть выданный экземпляр.

  Loan -> LocalDate"
  [loan]
  (:due loan))

(defn overdue-loans
  "Все выдачи, срок возврата которых уже прошёл, отсортированные от самой
  давней.

  Loans -> LocalDate -> [Loan]"
  [loans today]
  (sort-by :due (filter #(.isBefore ^java.time.LocalDate (:due %) today) (vals loans))))

(defn loan-history
  "Список всех книг, которые читатель когда-либо брал, с датами выдачи и
  возврата.

  [LoanEvent] -> ReaderId -> [LoanEvent]"
  [events reader-id]
  (filter #(= reader-id (:reader %)) events))

(defn mark-lost
  "Отмечает выданный экземпляр как утерянный читателем и выставляет ему
  стоимость книги к оплате.

  Loans -> CopyId -> Double -> Loans"
  [loans copy-id price]
  (update loans copy-id assoc :lost? true :charge price))

(defn reserve-copy
  "Ставит читателя в очередь ожидания на книгу, все экземпляры которой
  сейчас на руках.

  Queue -> Isbn -> ReaderId -> Queue"
  [queue isbn reader-id]
  (update queue isbn (fnil conj []) reader-id))

(defn cancel-reservation
  "Убирает читателя из очереди ожидания книги, когда она ему больше не
  нужна.

  Queue -> Isbn -> ReaderId -> Queue"
  [queue isbn reader-id]
  (update queue isbn #(vec (remove #{reader-id} %))))
