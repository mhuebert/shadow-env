# shadow-env

Clojure(Script) environment injection with shadow-cljs live-reload support

----

I often set up applications to read config from EDN files using something like [juxt/aero](https://github.com/juxt/aero), and run into a couple minor issues:

- how to get my cljs app to pick up changes in these EDN files without recompiling from scratch
- how to cleanly expose environment to both Clojure and ClojureScript while avoiding secrets being exposed in the wrong places

This small library provides:

- automatic propagation of changes made to environment files on each recompile
- explicit & simple control of what is exposed to ClojureScript vs Clojure

## Usage

in a shadow-cljs.edn build:

```clj
:build-hooks [(shadow-env.core/hook)]
```

in your app's env namespace:
```clj
(ns my-app.env
  (:refer-clojure :exclude [get])
  (:require [shadow-env.core :as env]))

;; write a Clojure function that returns variables to expose to :clj and :cljs.
;; the function must accept one variable, the shadow-cljs build-state
;; (which will be `nil` initially, before compile starts)
#?(:clj
    (defn read-env [build-state]
       {:clj  <exposed to Clojure>
        :cljs <exposed to ClojureScript>})

;; define & link a new var to your reader function.
;; you must pass a fully qualified symbol here, so syntax-quote (`) is useful.
;; in this example I use `get` as the name, because we can call Clojure maps
;; as functions, I like to alias my env namespace as `env`, and (env/get :some-key)
;; is very readable.
(env/link get `read-env)
```

usage elsewhere:
```clj
(ns my-app.client
  (:require [my-app.env :as env]))

(env/get :my/attribute)

```

## How it works

Every time your app recompiles, we

1) re-bind the `:clj` environment to the var you create using `env/link` (using `alter-var-root`), and
2) if the `:cljs` environment has changed, we invalidate the env's namespace so that it + its dependents will recompile.

## Example usage with aero

I like to use [juxt/aero](https://github.com/juxt/aero) to read EDN files from disk.
I use a handful of different files to separate config that is server-only, client-only,
common, and secret (ie. not in source control).

```clj
#?(:clj
   (defn read-env [build-state]
     (let [aero-config {:profile (or (System/getenv "ENV") :dev)}
           [common
            server
            secret
            client] (->> ["common.edn"
                          "server.edn"
                          ".secret.server.edn"
                          "client.edn"]
                         (map #(some-> (io/resource %)
                                       (aero/read-config aero-config))))]
       {:common common
        :clj (merge server secret)
        :cljs client})))

(env/link get `read-env)
```

## Dead code elimination (DCE)

ClojureScript can remove unused code automatically based on compile-time constants.
For this to work, we can't read from our environment map at runtime - instead, we can
write a tiny macro that runs at compile-time and reads from our env:

```clj
(env/link get `read-env)

(defmacro get-static [k]
  (clojure.core/get get k))

;; ClojureScript code can call (get-static :some/key) and the value will be replaced
;; at compile-time, enabling DCE
```

