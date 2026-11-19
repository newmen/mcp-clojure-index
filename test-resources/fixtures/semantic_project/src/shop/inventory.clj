(ns shop.inventory
  "Stock levels of products in the warehouse.")

(defn reserve-stock
  "Decrease the available stock of a product when an order is placed."
  [stock product-id quantity]
  (update stock product-id - quantity))

(defn restock-product
  "Add delivered units of a product back to the warehouse stock."
  [stock product-id quantity]
  (update stock product-id (fnil + 0) quantity))
