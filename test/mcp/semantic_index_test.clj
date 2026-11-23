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
                 qdrant/stored-layout (fn [_cfg#] nil)
                 qdrant/create-collection! (fn [_cfg# _vector-size#] nil)
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
  (is (= 6 (count (sut/stale-files (sut/create (services-config)) @fixture-index)))))

(deftest sync-embeds-every-search-unit-and-leaves-nothing-stale
  (let [points (fake-qdrant)
        semantic-index (sut/create (services-config))]
    (with-services points
      (sut/sync! semantic-index @fixture-index))
    (is (= (set (map :id (search-units/search-units @fixture-index)))
           (set (keys @points))))
    (is (= [] (sut/stale-files semantic-index @fixture-index)))))

(deftest sync-after-an-edit-re-embeds-only-the-changed-file
  (let [points (fake-qdrant)
        semantic-index (sut/create (services-config))
        inventory (fixture-file "src/shop/inventory.clj")
        edited (update-in @fixture-index [:files inventory :mtime] inc)
        upserted-files (atom [])]
    (with-services points
      (sut/sync! semantic-index @fixture-index)
      (testing "the edited file is stale until its embeddings are recomputed"
        (is (= [inventory] (sut/stale-files semantic-index edited))))
      (with-redefs [qdrant/upsert! (fn [_cfg units _vectors _mtime]
                                     (swap! upserted-files into (map :filename units)))]
        (sut/sync! semantic-index edited)))
    (is (= #{inventory} (set @upserted-files)))
    (is (= [] (sut/stale-files semantic-index edited)))))

(deftest sync-forgets-deleted-files
  (let [points (fake-qdrant)
        semantic-index (sut/create (services-config))
        inventory (fixture-file "src/shop/inventory.clj")
        without-inventory (update @fixture-index :files dissoc inventory)]
    (with-services points
      (sut/sync! semantic-index @fixture-index)
      (is (= [inventory] (sut/stale-files semantic-index without-inventory)))
      (sut/sync! semantic-index without-inventory))
    (is (= [] (sut/stale-files semantic-index without-inventory)))
    (is (not-any? (comp #{inventory} :filename) (vals @points)))))

(deftest sync-with-services-down-keeps-files-stale-and-does-not-throw
  (testing "Qdrant down"
    (let [semantic-index (sut/create (services-config))]
      (sut/sync! semantic-index @fixture-index)
      (is (= 6 (count (sut/stale-files semantic-index @fixture-index))))))
  (testing "Ollama down"
    (let [semantic-index (sut/create (services-config))]
      (with-services (fake-qdrant)
        (with-redefs [embeddings/generate (fn [_texts _cfg] (throw (ex-info "Connection refused" {})))]
          (sut/sync! semantic-index @fixture-index)))
      (is (= 6 (count (sut/stale-files semantic-index @fixture-index)))))))

(defn- prepare-calls
  "The collection operations `prepare!` performs on a collection found
   with `layout`, and the stale files after it; the embedding model returns
   vectors of size 3.

  Maybe CollectionLayout -> IO {:calls [Any] :stale Long}"
  [layout]
  (let [calls (atom [])
        semantic-index (sut/create (services-config))
        inventory (fixture-file "src/shop/inventory.clj")
        mtime (get-in @fixture-index [:files inventory :mtime])]
    (with-redefs [embeddings/generate (fn [texts _cfg] (mapv (constantly [0.1 0.2 0.3]) texts))
                  qdrant/stored-layout (fn [_cfg] layout)
                  qdrant/delete-collection! (fn [_cfg] (swap! calls conj :delete))
                  qdrant/create-collection! (fn [_cfg vector-size] (swap! calls conj [:create vector-size]))
                  qdrant/embedded-files (fn [_cfg] {inventory mtime})]
      (sut/prepare! semantic-index))
    {:calls @calls
     :stale (count (sut/stale-files semantic-index @fixture-index))}))

(def ^:private current-model
  (:ollama/embedding-model project/config))

(deftest prepare-creates-a-missing-collection-with-the-vector-size-of-the-model
  (is (= {:calls [[:create 3]] :stale 6} (prepare-calls nil))))

(deftest prepare-recreates-an-old-format-collection
  (is (= {:calls [:delete [:create 3]] :stale 6}
         (prepare-calls {:format 1 :model current-model}))))

(deftest prepare-recreates-a-collection-of-another-embedding-model
  (is (= {:calls [:delete [:create 3]] :stale 6}
         (prepare-calls {:format qdrant/payload-format :model "another-model"}))))

(deftest prepare-keeps-a-current-collection-and-its-embedded-files
  (is (= {:calls [[:create 3]] :stale 5}
         (prepare-calls {:format qdrant/payload-format :model current-model}))))
