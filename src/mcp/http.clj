(ns mcp.http
  "Shapes shared by the HTTP adapters of Qdrant (`mcp.qdrant`), Ollama and
   the reranker (`mcp.embeddings`)."
  (:require [clojure.spec.alpha :as s]))

;; JsonResponse: an HTTP response whose `:body` is parsed JSON with keyword
;; keys, {:status 200 :body {...}}. The body is nil when it was not JSON.

(s/def ::json-response (s/keys :req-un [::status ::body]))
