(ns com.moclojer.specs.moclojer
  (:require
   [clojure.data.json :as json]
   [clojure.string :as string]
   [com.moclojer.external-body.core :as ext-body]
   [com.moclojer.webhook :as webhook]
   [io.pedestal.http.route :as route]
   [selmer.parser :as selmer]))

(defn render-template
  [template request]
  (selmer/render (ext-body/->str template) request))

(defn enrich-external-body
  "Enriches the external body with a resolved path."
  [external-body request]
  (let [path (render-template (:path external-body) request)]
    (assoc external-body :path path)))

(defn build-body
  "Builds the body from the response."
  [response request]
  (let [external-body (:external-body response)]
    (cond
      external-body
      (-> external-body
          (enrich-external-body request)
          ext-body/type-identification
          (render-template request))
      :else (-> (:body response)
                (render-template request)))))

(defn webhook-condition
  "check `if` condition and return boolean value, default is true"
  [condition request]
  (let [template (str "{% if " condition " %}true{% else %}false{% endif %}")]
    (if (empty? condition)
      true
      (boolean (Boolean/valueOf (selmer/render (ext-body/->str template) request))))))

(defn generic-handler
  [response webhook-config]
  (fn [request]
    (when webhook-config
      (webhook/request-after-delay
       {:url (:url webhook-config)
        :condition (webhook-condition (:if webhook-config) request)
        :method (:method webhook-config)
        :body (render-template (:body webhook-config) request)
        :headers (or (json/read-str
                      (render-template
                       (reduce-kv
                        (fn [acc k v]
                          (assoc acc k (string/lower-case v)))
                        {}
                        (:headers webhook-config))
                       request))
                     (:headers request))
        :sleep-time (:sleep-time webhook-config)}))
    {:body    (build-body response request)
     :status  (:status response)
     :headers (into
               {}
               (map (fn [[k v]]
                      [(name k) (str v)]))
               (:headers response))}))

(defn generate-route-name
  [host path method]
  (str method "-" (or host "localhost") "-" (string/replace (string/replace path "/" "") ":" "--")))

(defn generate-method [method]
  (-> (or method "get")
      name
      string/lower-case))

(defn ->pedestal
  "generate routes from moclojer spec to pedestal"
  [spec]
  (->>
   (for [[[host path method] endpoints] (group-by (juxt :host :path :method)
                                                  (remove nil? (map :endpoint spec)))]
     (let [method (generate-method method)
           route-name (generate-route-name host path method)
           response (:response (first endpoints))
           webhook-config (:webhook (first endpoints))]
       (route/expand-routes
        #{{:host host}
          [path
           (keyword method)
           (generic-handler response webhook-config)
           :route-name (keyword route-name)]})))
   (mapcat identity)))
