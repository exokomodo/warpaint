(ns warpaint.dsl
  "Minimal s-expression shader DSL for Clojure.

   Emits GLSL 450 source from Clojure data, then compiles to SPIR-V via
   warpaint.compiler (shaderc in-process, glslc fallback).

   ## Quick start

   ```clojure
   (require '[warpaint.dsl :refer [defshader emit-glsl compile-shader]])

   ;; Define a shader var — compiles to SPIR-V at load time
   (defshader my-vert :vertex
     {:inputs  [{:name :in-pos :type :vec2 :location 0}]
      :outputs [{:name :frag-color :type :vec4 :location 0}]
      :push-constants [{:name :translation :type :vec2}
                       {:name :rotation    :type :float}
                       {:name :padding     :type :float}
                       {:name :color       :type :vec4}]}
     (let [^float c       (cos (.rotation pc))
           ^float s       (sin (.rotation pc))
           ^vec2  rotated (vec2 (- (* (.x in-pos) c) (* (.y in-pos) s))
                                (+ (* (.x in-pos) s) (* (.y in-pos) c)))
           ^vec2  final   (+ rotated (.translation pc))]
       (set! gl-Position (vec4 final 0.0 1.0))
       (set! frag-color (.color pc))))

   ;; my-vert is now a java.nio.ByteBuffer containing SPIR-V
   ```

   ## Shader descriptor map

   `compile-shader` and `emit-glsl` accept a map with these keys:

   | Key               | Type    | Description |
   |-------------------|---------|-------------|
   | `:stage`          | keyword | `:vertex` \\| `:fragment` \\| `:compute` |
   | `:inputs`         | vec     | `[{:name :kw :type :glsl-type :location N}]` |
   | `:outputs`        | vec     | same shape as `:inputs` |
   | `:push-constants` | vec     | `[{:name :kw :type :glsl-type}]` |
   | `:uniforms`       | vec     | `[{:name :kw :type :glsl-type :set N :binding N}]` |
   | `:const-arrays`   | vec     | `[{:name :kw :type :glsl-type :size N :values [...]}]` |
   | `:main`           | vec     | s-expression forms for `void main() {}` |

   ## Supported GLSL types (as keywords)

   `:float` `:int` `:uint` `:bool` `:vec2` `:vec3` `:vec4`
   `:mat2` `:mat3` `:mat4` `:ivec2` `:ivec3` `:ivec4` `:sampler2D`

   ## Expression syntax

   | Clojure form         | Emits            |
   |----------------------|------------------|
   | `(+ a b)`            | `(a + b)`        |
   | `(= a b)`            | `(a == b)`       |
   | `(.x v)`             | `v.x`            |
   | `(.translation pc)`  | `pc.translation` |
   | `(aget arr i)`       | `arr[i]`         |
   | `(set! dest val)`    | `dest = val`     |
   | `(vec4 x y z w)`     | `vec4(x, y, z, w)` |
   | `(if cond a b)`      | `(cond ? a : b)` |
   | `(when cond body…)`  | `if (cond) { … }` |
   | `(do stmts…)`        | statements       |
   | `(let [^T n v] …)`   | `T n = v; …`     |
   | `gl-Position`        | `gl_Position`    |
   | `gl-VertexIndex`     | `gl_VertexIndex` |
   | `gl-FragCoord`       | `gl_FragCoord`   |
   | `gl-PointSize`       | `gl_PointSize`   |

   Identifiers are kebab→camelCase: `:frag-color` → `fragColor`."
  (:require [warpaint.compiler :as compiler])
  (:import [java.io File]))

;; ---------------------------------------------------------------------------
;; Type map
;; ---------------------------------------------------------------------------

(def ^:private glsl-types
  {:float     "float"
   :int       "int"
   :uint      "uint"
   :bool      "bool"
   :vec2      "vec2"
   :vec3      "vec3"
   :vec4      "vec4"
   :mat2      "mat2"
   :mat3      "mat3"
   :mat4      "mat4"
   :ivec2     "ivec2"
   :ivec3     "ivec3"
   :ivec4     "ivec4"
   :sampler2D "sampler2D"})

(defn- glsl-type [k]
  (or (get glsl-types k)
      (throw (ex-info (str "Unknown GLSL type: " k) {:type k}))))

;; ---------------------------------------------------------------------------
;; Identifier helpers
;; ---------------------------------------------------------------------------

(defn- ident
  "Convert a Clojure keyword/symbol to a camelCase GLSL identifier.
   :in-pos → inPos, :frag-color → fragColor."
  [k]
  (let [s     (name k)
        parts (clojure.string/split s #"-")]
    (str (first parts)
         (apply str (map clojure.string/capitalize (rest parts))))))

;; ---------------------------------------------------------------------------
;; Expression emitter
;; ---------------------------------------------------------------------------

(declare emit-expr)

(defn- emit-args [args]
  (clojure.string/join ", " (map emit-expr args)))

(defn- emit-expr [form]
  (cond
    (nil? form)     "null"
    (true? form)    "true"
    (false? form)   "false"
    (integer? form) (str form)
    (float? form)   (let [s (str form)]
                      (if (.contains s ".") s (str s ".0")))
    (double? form)  (let [s (str form)]
                      (if (.contains s ".") s (str s ".0")))
    (keyword? form) (ident form)

    (symbol? form)
    (case form
      gl-Position    "gl_Position"
      gl-VertexIndex "gl_VertexIndex"
      gl-FragCoord   "gl_FragCoord"
      gl-PointSize   "gl_PointSize"
      (let [s     (name form)
            parts (clojure.string/split s #"-")]
        (str (first parts)
             (apply str (map clojure.string/capitalize (rest parts))))))

    (seq? form)
    (let [[op & args] form]
      (cond
        ;; Field access: (.foo expr) → expr.foo  (.uv-pos v) → v.uvPos
        (and (symbol? op) (.startsWith (name op) "."))
        (let [field  (subs (name op) 1)
              parts  (clojure.string/split field #"-")
              gfield (str (first parts)
                          (apply str (map clojure.string/capitalize (rest parts))))]
          (str (emit-expr (first args)) "." gfield))

        ;; Array index: (aget arr i) → arr[i]
        (= op 'aget)
        (str (emit-expr (first args)) "[" (emit-expr (second args)) "]")

        ;; Arithmetic
        ('#{+ - * /} op)
        (str "(" (emit-expr (first args)) " " (name op) " " (emit-expr (second args)) ")")

        ;; Compound assignment: (+= var val) → var += val
        (contains? #{"+=" "-=" "*="} (name op))
        (str (emit-expr (first args)) " " (name op) " " (emit-expr (second args)))

        ;; Comparison / logical
        ('#{= not= < > <= >= and or not} op)
        (let [glsl-op (case op
                        =    "=="  not= "!="
                        <    "<"   >    ">"
                        <=   "<="  >=   ">="
                        and  "&&"  or   "||"
                        not  "!")]
          (if (= op 'not)
            (str "(!" (emit-expr (first args)) ")")
            (str "(" (emit-expr (first args)) " " glsl-op " " (emit-expr (second args)) ")")))

        ;; Assignment
        (= op 'set!)
        (str (emit-expr (first args)) " = " (emit-expr (second args)))

        ;; Let — each binding requires a type hint: (let [^float c (cos x)] …)
        (= op 'let)
        (let [[bindings & body] args
              pairs (partition 2 bindings)
              decls (map (fn [[n val]]
                           (let [t (-> n meta :tag)]
                             (when-not t
                               (throw (ex-info
                                       (str "let binding '" n
                                            "' requires a type hint, e.g. ^float " n)
                                       {:sym n})))
                             (str (glsl-type (keyword (name t)))
                                  " " (emit-expr n)
                                  " = " (emit-expr val) ";")))
                         pairs)
              stmts (map #(str (emit-expr %) ";") body)]
          (clojure.string/join "\n    " (concat decls stmts)))

        ;; when → if block
        (= op 'when)
        (str "if (" (emit-expr (first args)) ") {\n    "
             (clojure.string/join "\n    "
                                  (map #(str (emit-expr %) ";") (rest args)))
             "\n  }")

        ;; if → ternary
        (= op 'if)
        (str "(" (emit-expr (first args))
             " ? " (emit-expr (second args))
             " : " (emit-expr (nth args 2)) ")")

        ;; do / begin
        (= op 'do)
        (clojure.string/join "\n    "
                             (map #(str (emit-expr %) ";") args))

        ;; Everything else: function call
        :else
        (str (name op) "(" (emit-args args) ")")))

    :else (str form)))

;; ---------------------------------------------------------------------------
;; Declaration emitters
;; ---------------------------------------------------------------------------

(defn- emit-inputs [inputs]
  (map (fn [{:keys [name type location]}]
         (str "layout(location = " location ") in "
              (glsl-type type) " " (ident name) ";"))
       inputs))

(defn- emit-outputs [outputs]
  (map (fn [{:keys [name type location]}]
         (str "layout(location = " location ") out "
              (glsl-type type) " " (ident name) ";"))
       outputs))

(defn- emit-push-constants [fields]
  (when (seq fields)
    (let [field-lines (map (fn [{:keys [name type]}]
                             (str "    " (glsl-type type) " " (ident name) ";"))
                           fields)]
      [(str "layout(push_constant) uniform PushConstants {\n"
            (clojure.string/join "\n" field-lines)
            "\n} pc;")])))

(defn- emit-uniforms [uniforms]
  (map (fn [{:keys [name type set binding]}]
         (str "layout(set = "     (or set 0)
              ", binding = " (or binding 0)
              ") uniform " (glsl-type type) " " (ident name) ";"))
       uniforms))

(defn- emit-const-arrays [arrays]
  (map (fn [{:keys [name type size values]}]
         (str "const " (glsl-type type) " " (ident name)
              "[" size "] = " (glsl-type type) "[](\n    "
              (clojure.string/join ",\n    " (map emit-expr values))
              "\n);"))
       arrays))

;; ---------------------------------------------------------------------------
;; Body emitter
;; ---------------------------------------------------------------------------

(defn- emit-body [forms]
  (clojure.string/join "\n    "
                       (map (fn [f]
                              (let [s (emit-expr f)]
                                (if (.endsWith s ";") s (str s ";"))))
                            forms)))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn emit-glsl
  "Emit a GLSL 450 string from a shader descriptor map.
   Does not compile — useful for inspecting generated source or testing.

   See namespace docstring for descriptor map keys."
  [{:keys [inputs outputs push-constants uniforms const-arrays main]}]
  (let [lines (concat
               ["#version 450" ""]
               (emit-inputs (or inputs []))
               (when (seq inputs) [""])
               (emit-outputs (or outputs []))
               (when (seq outputs) [""])
               (emit-push-constants push-constants)
               (when (seq push-constants) [""])
               (emit-uniforms (or uniforms []))
               (when (seq uniforms) [""])
               ["void main() {"]
               (when (seq const-arrays)
                 (concat (emit-const-arrays const-arrays) [""]))
               [(str "    " (emit-body (or main [])))]
               ["}"])]
    (clojure.string/join "\n" (filter some? lines))))

(defn compile-shader
  "Compile a shader descriptor map to a SPIR-V ByteBuffer.
   Writes a temporary .glsl file, compiles with shaderc (glslc fallback),
   and returns a java.nio.ByteBuffer.

   See namespace docstring for descriptor map keys."
  [descriptor]
  (let [stage    (:stage descriptor)
        ext      (case stage
                   :vertex   ".vert"
                   :fragment ".frag"
                   :compute  ".comp"
                   (throw (ex-info "Unknown shader stage" {:stage stage})))
        glsl     (emit-glsl descriptor)
        tmp      (doto (File/createTempFile "warpaint_" ext)
                   (.deleteOnExit))
        tmp-path (.getAbsolutePath tmp)]
    (spit tmp-path glsl)
    (compiler/compile-glsl tmp-path)
    (compiler/load-spirv (str tmp-path ".spv"))))

(defn load-edn
  "Load a shader descriptor from an EDN file and compile it to SPIR-V.
   Stage is inferred from the file extension (.vert / .frag / .comp).
   Returns a java.nio.ByteBuffer.

   Example:
     (warpaint.dsl/load-edn \"shaders/my-shader.vert.edn\")"
  [path]
  (let [descriptor (clojure.edn/read-string (slurp (clojure.java.io/file path)))
        stage      (cond
                     (.contains ^String path ".vert") :vertex
                     (.contains ^String path ".frag") :fragment
                     (.contains ^String path ".comp") :compute
                     :else (throw (ex-info
                                   (str "Cannot infer shader stage from path: " path)
                                   {:path path})))]
    (compile-shader (assoc descriptor :stage stage))))

(defmacro defshader
  "Define a named shader var. Compiles to a SPIR-V ByteBuffer at load time.

   Usage:
     (defshader my-vert :vertex
       {:inputs  [{:name :in-pos :type :vec2 :location 0}]
        :outputs [{:name :frag-color :type :vec4 :location 0}]
        :push-constants [{:name :color :type :vec4}]}
       (set! gl-Position (vec4 (.translation pc) 0.0 1.0))
       (set! frag-color (.color pc)))

   `body` forms become the :main of the descriptor.
   The resulting var holds a java.nio.ByteBuffer of SPIR-V."
  [shader-name stage descriptor & body]
  `(def ~shader-name
     (compile-shader (assoc ~descriptor
                            :stage ~stage
                            :main (quote ~(vec body))))))
