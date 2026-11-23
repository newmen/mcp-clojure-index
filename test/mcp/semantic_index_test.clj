(ns mcp.semantic-index-test
  "Keeping Qdrant's embeddings of Search units in step with the precise
   index; Qdrant and Ollama are replaced by in-memory stand-ins."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [mcp.code-index :as code-index]
            [mcp.embeddings :as embeddings]
            [mcp.qdrant :as qdrant]
            [mcp.search-units :as search-units]
            [mcp.semantic-index :as sut]
            [mcp.test-project :as project])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(set! *warn-on-reflection* true)

(def ^:private fixture-root
  (.getCanonicalPath (File. "test-resources/fixtures/semantic_project")))

(def ^:private fixture-index
  (delay (code-index/build (assoc project/config :index/root-path fixture-root))))

(defn- temp-dir
  "Creates a fresh temporary directory and returns its path.

  () -> IO String"
  []
  (str (Files/createTempDirectory "semantic-index-test" (make-array FileAttribute 0))))

(defn- services-config
  "The config of the fixture project, with unreachable Qdrant and Ollama
   and a fresh embedding cache.

  () -> IO Config"
  []
  (assoc project/config
         :index/root-path fixture-root
         :qdrant/port 19999
         :ollama/url "http://localhost:19999/v1"
         :embedding/cache-dir (temp-dir)))

(defn- fake-qdrant
  "Creates an in-memory Qdrant collection: {point-id unit}.

  () -> IO (Atom {UUID SearchUnit})"
  []
  (atom {}))

(defmacro ^:private with-services
  "Runs `body` with Qdrant backed by `points` and Ollama embedding every
   text as a constant vector.

  Atom {UUID SearchUnit} -> & Any -> IO a"
  [points & body]
  `(with-redefs [embeddings/generate (fn [texts# _cfg#] (mapv (constantly [0.1 0.2]) texts#))
                 qdrant/stored-format (fn [_cfg#] nil)
                 qdrant/create-collection! (fn [_cfg#] nil)
                 qdrant/embedded-files (fn [_cfg#] {})
                 qdrant/delete! (fn [_cfg# file-path#]
                                  (swap! ~points #(into {} (remove (comp #{file-path#} :filename val)) %)))
                 qdrant/upsert! (fn [_cfg# units# _vectors# _mtime#]
                                  (swap! ~points into (map (juxt :id identity)) units#))]
     ~@body))

(defn- fixture-file
  "The canonical path of the fixture file `relative-path`.

  String -> IO String"
  [relative-path]
  (.getCanonicalPath (io/file fixture-root relative-path)))

(deftest every-file-is-stale-before-the-first-sync
  (is (= 6 (count (sut/stale-files (sut/make-state) @fixture-index)))))

(deftest sync-embeds-every-search-unit-and-leaves-nothing-stale
  (let [points (fake-qdrant)
        state (sut/make-state)]
    (with-services points
      (sut/sync! (services-config) state @fixture-index))
    (is (= (set (map :id (search-units/search-units @fixture-index)))
           (set (keys @points))))
    (is (= [] (sut/stale-files state @fixture-index)))))

(deftest sync-after-an-edit-re-embeds-only-the-changed-file
  (let [points (fake-qdrant)
        state (sut/make-state)
        inventory (fixture-file "src/shop/inventory.clj")
        edited (update-in @fixture-index [:files inventory :mtime] inc)
        upserted-files (atom [])]
    (with-services points
      (sut/sync! (services-config) state @fixture-index)
      (testing "the edited file is stale until its embeddings are recomputed"
        (is (= [inventory] (sut/stale-files state edited))))
      (with-redefs [qdrant/upsert! (fn [_cfg units _vectors _mtime]
                                     (swap! upserted-files into (map :filename units)))]
        (sut/sync! (services-config) state edited)))
    (is (= #{inventory} (set @upserted-files)))
    (is (= [] (sut/stale-files state edited)))))

(deftest sync-forgets-deleted-files
  (let [points (fake-qdrant)
        state (sut/make-state)
        inventory (fixture-file "src/shop/inventory.clj")
        without-inventory (update @fixture-index :files dissoc inventory)]
    (with-services points
      (sut/sync! (services-config) state @fixture-index)
      (is (= [inventory] (sut/stale-files state without-inventory)))
      (sut/sync! (services-config) state without-inventory))
    (is (= [] (sut/stale-files state without-inventory)))
    (is (not-any? (comp #{inventory} :filename) (vals @points)))))

(deftest sync-with-services-down-keeps-files-stale-and-does-not-throw
  (testing "Qdrant down"
    (let [state (sut/make-state)]
      (sut/sync! (services-config) state @fixture-index)
      (is (= 6 (count (sut/stale-files state @fixture-index))))))
  (testing "Ollama down"
    (let [state (sut/make-state)]
      (with-services (fake-qdrant)
        (with-redefs [embeddings/generate (fn [_texts _cfg] (throw (ex-info "Connection refused" {})))]
          (sut/sync! (services-config) state @fixture-index)))
      (is (= 6 (count (sut/stale-files state @fixture-index)))))))

(deftest prepare-recreates-an-old-format-collection
  (let [calls (atom [])]
    (with-redefs [qdrant/stored-format (fn [_cfg] 1)
                  qdrant/delete-collection! (fn [_cfg] (swap! calls conj :delete))
                  qdrant/create-collection! (fn [_cfg] (swap! calls conj :create))
                  qdrant/embedded-files (fn [_cfg] {})]
      (sut/prepare! (services-config) (sut/make-state)))
    (is (= [:delete :create] @calls))))

(deftest prepare-keeps-a-current-collection-and-its-embedded-files
  (let [calls (atom [])
        state (sut/make-state)
        inventory (fixture-file "src/shop/inventory.clj")
        mtime (get-in @fixture-index [:files inventory :mtime])]
    (with-redefs [qdrant/stored-format (fn [_cfg] qdrant/payload-format)
                  qdrant/delete-collection! (fn [_cfg] (swap! calls conj :delete))
                  qdrant/create-collection! (fn [_cfg] (swap! calls conj :create))
                  qdrant/embedded-files (fn [_cfg] {inventory mtime})]
      (sut/prepare! (services-config) state))
    (is (= [:create] @calls))
    (is (= 5 (count (sut/stale-files state @fixture-index))))))
