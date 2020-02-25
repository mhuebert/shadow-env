(ns shadow-env.core
  #?(:cljs (:require-macros [shadow-env.core :as env])))

#?(:clj
   (defonce !registry (atom {})))

#?(:clj
   (defn- read-env [build-state read-fn]
     (let [{:keys [clj cljs]} (read-fn build-state)]
       (assoc clj ::cljs cljs))))

#?(:clj
   (defn- invalidate-var
     [build-state the-var]
     (let [resource-id (get-in build-state [:sym->id (.-name (:ns (meta the-var)))])]
       (update build-state :output dissoc resource-id))))

#?(:clj
   (defn- update-env!
     [build-state env-var reader-fn]
     (let [env-value (read-env build-state reader-fn)
           cljs-changed? (not= (::cljs env-value)
                               (get-in build-state [::cljs env-var]))]
       (alter-var-root env-var (constantly env-value))
       (cond-> build-state
               cljs-changed?
               (-> (assoc-in [::cljs env-var] env-value)
                   (invalidate-var env-var))))))

#?(:clj
   (defn cljs? [env]
     (boolean (:ns env))))

#?(:clj
   (defn dequote [sym]
     (cond-> sym (seq? sym) (second))))

#?(:clj
   (defmacro link
     "defines a var bound to value of `reader-sym`, a fully qualified symbol pointing to a
      Clojure function"
     [name reader-sym]
     (let [reader-sym (dequote reader-sym)
           qualified-env-sym (symbol (str (.-name *ns*)) (str name))]
       (assert (qualified-symbol? reader-sym) "reader-sym must be qualified symbol")
       (if (cljs? &env)
         (let [cljs-value (or (::cljs @(requiring-resolve qualified-env-sym))
                              (::cljs (read-env nil @(requiring-resolve reader-sym))))]
           `(def ~name ~cljs-value))
         `(do
            (def ~name (~reader-sym nil))
            (swap! !registry assoc (var ~qualified-env-sym) ~reader-sym)
            (var ~qualified-env-sym))))))

#?(:clj
   (defn hook
     {:shadow.build/stages #{:compile-prepare}}
     [build-state]
     (reduce-kv update-env! build-state @!registry)))