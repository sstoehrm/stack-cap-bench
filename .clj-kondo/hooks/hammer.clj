(ns hooks.hammer)

(defn- bindings
  "A vector-literal init is a db path, not a vector value."
  [bs]
  (vec (mapcat (fn [[sym init]] [sym (if (vector? init) `(get ~init 0) init)])
               (partition 2 bs))))

(defmacro defc
  "(defc name [props*] [bindings*] body+)"
  [cname props & [bs & body]]
  `(defn ~cname ~props (let ~(bindings bs) ~@body)))

(defmacro defloop
  "(defloop name [props*] [bindings*] opts? draw-fn)"
  [cname props bs & more]
  `(defn ~cname ~props (let ~(bindings bs) ~@more)))
