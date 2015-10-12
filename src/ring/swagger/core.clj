(ns ring.swagger.core
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [schema.core :as s]
            [plumbing.core :refer :all]

            ; the json-encoders are registered in ring.swagger.json
            ; there are client projects depending on ring.swagger.core ns to get the json encodings
            ; thre real public api - ring.swagger.swagger2 depends on this ns, so it gets these too.
            ; FIXME: global side effects -> separate import with 1.0
            ring.swagger.json

            [ring.swagger.common :refer :all]
            [schema-tools.walk :as stw]
            [linked.core :as linked]
            [clojure.string :as string]
            [org.tobereplaced.lettercase :as lc])
  (:import (clojure.lang IMapEntry)))

;;
;; Helpers
;;

(defn required-keys [schema]
  (filterv s/required-key? (keys schema)))

(defn strict-schema
  "removes open keys from schema"
  [schema]
  {:pre [(map? schema)]}
  (dissoc schema s/Keyword))

;;
;; Schema transformations
;;

(defn- full-name [path] (->> path (map name) (map lc/capitalized) (apply str) symbol))

(defn map-entry? [x]
  (instance? IMapEntry x))

(defn peek-schema
  "Recurisively seeks the form with schema-name.
   Walks over sets, vectors and Schema predicates."
  [schema]
  (let [it (atom nil)]
    ((fn walk [x]
       (stw/walk
         x
         (fn [x]
           (if (and (plain-map? x) (s/schema-name x))
             (do (reset! it x) x)
             (walk x)))
         identity)) [schema])
    @it))

(defn name-schemas [names schema]
  (stw/walk
    schema
    (fn [x]
      (if (map-entry? x)
        [(key x)
         (name-schemas
           (conj names
                 (if (s/specific-key? (key x))
                   (s/explicit-schema-key (key x))
                   (gensym (pr-str (key x))))) (val x))]
        (name-schemas names x)))
    (fn [x]
      (if (plain-map? x)
        (if-not (s/schema-name x)
          (with-meta x {:name (full-name names)})
          x)
        x))))

(defn with-named-sub-schemas
  "Traverses a schema tree of Maps, Sets and Sequences and add Schema-name to all
   anonymous maps between the root and any named schemas in thre tree. Names of the
   schemas are generated by the following: Root schema name (or a generated name) +
   all keys in the path CamelCased"
  ([schema] (with-named-sub-schemas schema "schema"))
  ([schema prefix]
   (name-schemas [(or (s/schema-name schema) (gensym prefix))] schema)))

;; NOTE: silently ignores non-map schemas
(defn collect-models
  "Walks through the data structure and collects all Schemas
  into a map schema-name->#{values}. Note: schame-name can link
  to sevetal implementations."
  [x]
  (let [schemas (atom {})]
    (walk/prewalk
      (fn [x]
        (when-let [schema-name (and (plain-map? x) (s/schema-name x))]
          (let [schema (if (var? x) @x x)]
            (swap!
              schemas update-in [schema-name]
              (fn [x] (conj (or x (linked/set)) schema)))))
        x)
      x)
    @schemas))

;;
;; duplicates
;;

(defn ignore-duplicate-schemas [schema-name values]
  [schema-name (first values)])

; FIXME: custom predicates & regexps don't work
(defn fail-on-duplicate-schema! [schema-name values]
  (if-not (seq (rest values))
    [schema-name (first values)]
    (throw
      (IllegalArgumentException.
        (str
          "Looks like you're trying to define two models with the same name ("
          schema-name "), but different values:\n\n" (string/join "\n\n" values) "\n\n"
          "There is no way to create valid api docs with this setup. You may have
          multiple namespaces defining same Schema names or you have created copies"
          "of the scehmas with clojure.core fn's like \"select-keys\". Please check"
          "out schema-tools.core -transformers.")))))

(defn handle-duplicate-schemas [f schemas]
  (into
    (empty schemas)
    (for [[k v] schemas]
      (f k v))))

;;
;; Route generation
;;

(defn path-params [s]
  (map (comp keyword second) (re-seq #":(.[^:|(/]*)[/]?" s)))

(defn swagger-path [uri]
  (str/replace uri #":([^/]+)" "{$1}"))

(defn join-paths
  "Join several paths together with \"/\". If path ends with a slash,
   another slash is not added."
  [& paths]
  (str/replace (str/replace (str/join "/" (remove nil? paths)) #"([/]+)" "/") #"/$" ""))

(defn context
  "Context of a request. Defaults to \"\", but has the
   servlet-context in the legacy app-server environments."
  [{:keys [servlet-context]}]
  (if servlet-context (.getContextPath servlet-context) ""))

(defn basepath
  "extract a base-path from ring request. Doesn't return default ports
   and reads the header \"x-forwarded-proto\" only if it's set to value
   \"https\". (e.g. your ring-app is behind a nginx reverse https-proxy).
   Adds possible servlet-context when running in legacy app-server."
  [{:keys [scheme server-name server-port headers] :as request}]
  (let [x-forwarded-proto (headers "x-forwarded-proto")
        context (context request)
        scheme (if (= x-forwarded-proto "https") "https" (name scheme))
        port (if (#{80 443} server-port) "" (str ":" server-port))]
    (str scheme "://" server-name port context)))
