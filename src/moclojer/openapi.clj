(ns moclojer.openapi
  (:require [clojure.string :as string]
            [io.pedestal.http.route :as route]))

;; TODO: JSON Pointer library
(def path-item->operation
  "Convert path item to http method"
  #{"get" "put" "post" "delete" "options" "head" "patch" "trace"})

(defn json-pointer-escape-token
  "https://datatracker.ietf.org/doc/html/rfc6901"
  [s]
  (-> s
      (string/replace #"~" "~0")
      (string/replace #"/" "~1")))

(defn json-pointer-unescape-token
  "https://datatracker.ietf.org/doc/html/rfc6901"
  [s]
  (-> s
      (string/replace #"~1" "/")
      (string/replace #"~0" "~")))

(defn json-pointer->path
  "Turns JSON Ponter reprsentation into clojure 'path'.
  Example:
  (get-in {\"a\" {\"b\" 42}} (json-pointer->path \"/a/b\"))
  => 42"
  [pointer]
  (mapv json-pointer-unescape-token (rest (string/split pointer #"/"))))

(defn json-path->pointer
  "Turn a clojure sequence of strings into a JSON pointer"
  [coll]
  (string/join "/"
               (cons "" (map json-pointer-escape-token coll))))

(def generate-response
  "Generate a response object from a response object in the OpenAPI spec"
  {:name  ::generate-response
   :enter (fn [{::keys [operation]
                :as    ctx}]
            (assoc ctx :response
                       (if-let [{:strs [status body headers]} (get operation "x-mockResponse")]
                         {:body    body
                          :headers headers
                          :status  status}
                         {:status 501})))})

(defn with-mocks
  "Generate a mock response for a given operation"
  [openapi mocks]
  (let [op->path (into {}
                       (mapcat (fn [[path path-item]]
                                 (for [[method operation] path-item
                                       :when              (contains? path-item->operation method)
                                       :when              (contains? operation "operationId")]
                                   {(get operation "operationId") ["paths" path method]})))
                       (get openapi "paths"))]
    (reduce-kv (fn [openapi pointer-or-operation mock]
                 (let [path (or (op->path pointer-or-operation)
                                (json-pointer->path pointer-or-operation))]
                   (assoc-in openapi (conj path "x-mockResponse")
                             mock)))
               openapi mocks)))

(defn openapi-path->pedestal-path
  "Convert a path from openapi to pedestal"
  [path]
  ;; TODO: Handle wildcards
  ;; https://github.com/OAI/OpenAPI-Specification/issues/291
  ;; https://datatracker.ietf.org/doc/html/rfc6570
  (string/replace path
                  #"\{([^}]+)\}"
                  (fn [x]
                    (str ":" (second x)))))

(defn resolve-ref
  "Resolve a reference to a schema"
  [root {:strs [$ref]
         :as   object}]
  (if $ref
    (get-in root (json-pointer->path $ref))
    object))

(defn generate-pedestal-route
  "Generate a Pedestal route from an OpenAPI specification"
  [config]
  (sequence (mapcat
             (fn [[path path-item]]
                  (sequence
                    (mapcat (fn [[method operation]]
                              (when (contains? path-item->operation method)
                                (route/expand-routes
                                  #{[(openapi-path->pedestal-path path)
                                     (keyword method)
                                     [{:name  ::add-operation
                                       :enter (fn [ctx]
                                                (assoc ctx
                                                  ::path path
                                                  ::method method
                                                  ::openapi config
                                                  ::path-item path-item
                                                  ::operation operation))}
                                      generate-response]
                                     :route-name (keyword (or (get operation "operationId")
                                                              (json-path->pointer [path method])))]}))))
                    (resolve-ref config path-item))))
              (get config "paths")))
