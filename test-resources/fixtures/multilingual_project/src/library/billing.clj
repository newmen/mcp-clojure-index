(ns library.billing
  "Оплата штрафов и других начислений читателей.")

(defn pay-fine
  "Принимает от читателя оплату штрафа и уменьшает его долг на внесённую
  сумму.

  Debts -> ReaderId -> Double -> Debts"
  [debts reader-id amount]
  (update debts reader-id - amount))

(defn waive-fine
  "Прощает читателю штраф целиком, например по решению заведующего.

  Debts -> ReaderId -> Debts"
  [debts reader-id]
  (dissoc debts reader-id))

(defn daily-rate
  "Ставка штрафа за один день просрочки в зависимости от категории книги:
  редкие издания стоят дороже.

  Book -> Double"
  [book]
  (if (:rare? book) 50.0 10.0))

(defn receipt
  "Формирует квитанцию об оплате для печати.

  ReaderId -> Double -> LocalDate -> String"
  [reader-id amount date]
  (str reader-id " " amount " " date))

(defn monthly-income
  "Сумма штрафов, полученных библиотекой за каждый месяц.

  [Payment] -> {YearMonth Double}"
  [payments]
  (reduce (fn [acc {:keys [month amount]}] (update acc month (fnil + 0) amount)) {} payments))
