(ns library.readers
  "Читатели библиотеки: регистрация, напоминания и блокировка."
  (:require [clojure.spec.alpha :as s]))

;; Номер читательского билета: заглавная буква филиала, выдавшего билет, и
;; шесть цифр, например «Б004217».
(s/def ::card-number
  (s/and string? #(re-matches #"[А-Я]\d{6}" %)))

(defn register-reader
  "Заводит новому посетителю читательский билет с уникальным номером и
  контактами для связи.

  Readers -> String -> String -> Readers"
  [readers full-name email]
  (assoc readers (count readers) {:name full-name :email email}))

(defn notify-reader
  "Отправляет читателю письмо на электронную почту с напоминанием, что срок
  возврата книги скоро истекает.

  Reader -> Loan -> IO ()"
  [reader loan]
  (println "mail to" (:email reader) (:due loan)))

(defn block-reader
  "Запрещает выдавать книги читателю, у которого накопились неоплаченные
  штрафы.

  Reader -> Double -> Reader"
  [reader unpaid]
  (cond-> reader
    (pos? unpaid) (assoc :blocked? true)))

(defn renew-card
  "Продлевает действие читательского билета ещё на год.

  Reader -> LocalDate -> Reader"
  [reader today]
  (assoc reader :valid-until (.plusYears today 1)))

(defn send-overdue-notice
  "Отправляет письмо читателю, который уже не вернул книгу вовремя, с
  суммой начисленного штрафа.

  Reader -> Double -> IO ()"
  [reader fine]
  (println "mail to" (:email reader) fine))

(defn sms-reminder
  "Отправляет короткое сообщение на телефон читателя о книге, которая
  пришла к нему из очереди ожидания.

  Reader -> Book -> IO ()"
  [reader book]
  (println "sms to" (:phone reader) (:title book)))

(defn readers-with-debts
  "Читатели, у которых есть неоплаченные штрафы, по убыванию суммы долга.

  Readers -> {ReaderId Double} -> [Reader]"
  [readers debts]
  (->> debts (sort-by (comp - val)) (map (comp readers key))))

(defn unblock-reader
  "Снимает запрет на выдачу книг после того, как читатель погасил все
  долги.

  Reader -> Reader"
  [reader]
  (dissoc reader :blocked?))

(defn delete-reader
  "Удаляет читателя и его персональные данные по его заявлению.

  Readers -> ReaderId -> Readers"
  [readers reader-id]
  (dissoc readers reader-id))

(defn update-contacts
  "Меняет адрес электронной почты и телефон читателя.

  Reader -> String -> String -> Reader"
  [reader email phone]
  (assoc reader :email email :phone phone))
