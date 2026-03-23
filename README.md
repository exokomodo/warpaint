# warpaint

A Clojure DSL for writing GLSL 450 shaders as s-expressions, with in-process SPIR-V compilation via [lwjgl-shaderc](https://www.lwjgl.org/).

Write your shaders in Clojure. Get SPIR-V out.

[![Clojars Project](https://img.shields.io/clojars/v/com.exokomodo/warpaint.svg)](https://clojars.org/com.exokomodo/warpaint)

## Installation

```clojure
;; project.clj
[com.exokomodo/warpaint "0.1.0"]
```

```clojure
;; deps.edn
com.exokomodo/warpaint {:mvn/version "0.1.0"}
```

## Quick Start

```clojure
(require '[warpaint.dsl :refer [defshader emit-glsl compile-shader]])

;; Define a shader — compiles to SPIR-V at load time
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

;; my-vert is a java.nio.ByteBuffer containing SPIR-V
;; Pass it wherever you'd pass a VkShaderModuleCreateInfo buffer
```

## API

### `defshader`

Defines a named var. Compiles to SPIR-V at load time.

```clojure
(defshader name :stage descriptor & body-forms)
```

### `compile-shader`

Compile a descriptor map at runtime. Returns a `java.nio.ByteBuffer`.

```clojure
(compile-shader {:stage :vertex :inputs [...] :main [...]})
```

### `emit-glsl`

Emit GLSL source as a string without compiling. Useful for debugging.

```clojure
(emit-glsl {:stage :vertex :inputs [...] :main [...]})
;; => "#version 450\n..."
```

### `load-edn`

Load a shader descriptor from an EDN file and compile it. Stage is inferred from the file extension (`.vert` / `.frag` / `.comp`).

```clojure
(load-edn "shaders/my-shader.vert.edn")
```

## Shader Descriptor Map

| Key               | Description |
|-------------------|-------------|
| `:stage`          | `:vertex` \| `:fragment` \| `:compute` |
| `:inputs`         | `[{:name :kw :type :glsl-type :location N}]` |
| `:outputs`        | `[{:name :kw :type :glsl-type :location N}]` |
| `:push-constants` | `[{:name :kw :type :glsl-type}]` — emitted as `pc` block |
| `:uniforms`       | `[{:name :kw :type :glsl-type :set N :binding N}]` |
| `:const-arrays`   | `[{:name :kw :type :glsl-type :size N :values [...]}]` |
| `:main`           | s-expression forms for `void main()` |

## Supported GLSL Types

`:float` `:int` `:uint` `:bool` `:vec2` `:vec3` `:vec4` `:mat2` `:mat3` `:mat4` `:ivec2` `:ivec3` `:ivec4` `:sampler2D`

## Expression Reference

| Clojure                 | GLSL                    |
|-------------------------|-------------------------|
| `(+ a b)`               | `(a + b)`               |
| `(- a b)`               | `(a - b)`               |
| `(* a b)`               | `(a * b)`               |
| `(/ a b)`               | `(a / b)`               |
| `(= a b)`               | `(a == b)`              |
| `(not= a b)`            | `(a != b)`              |
| `(< a b)`               | `(a < b)`               |
| `(and a b)`             | `(a && b)`              |
| `(or a b)`              | `(a \|\| b)`             |
| `(not a)`               | `(!a)`                  |
| `(.x v)`                | `v.x`                   |
| `(.translation pc)`     | `pc.translation`        |
| `(.uv-pos v)`           | `v.uvPos`               |
| `(aget arr i)`          | `arr[i]`                |
| `(set! dest val)`       | `dest = val`            |
| `(vec4 x y z w)`        | `vec4(x, y, z, w)`      |
| `(normalize v)`         | `normalize(v)`          |
| `(cos x)`               | `cos(x)`                |
| `(if cond a b)`         | `(cond ? a : b)`        |
| `(when cond body…)`     | `if (cond) { … }`       |
| `(do stmts…)`           | statements              |
| `(let [^T n v] …)`      | `T n = v; …`            |
| `gl-Position`           | `gl_Position`           |
| `gl-VertexIndex`        | `gl_VertexIndex`        |
| `gl-FragCoord`          | `gl_FragCoord`          |
| `gl-PointSize`          | `gl_PointSize`          |

Identifiers follow kebab→camelCase: `:frag-color` → `fragColor`.

### `let` bindings require type hints

```clojure
;; ✅ correct
(let [^float c (cos x)
      ^vec2  p (vec2 0.0 0.0)]
  ...)

;; ❌ will throw — type hint required
(let [c (cos x)] ...)
```

## EDN Shader Format

You can keep shaders as EDN data files:

```edn
;; shaders/my-shader.vert.edn
{:inputs  [{:name :in-pos :type :vec2 :location 0}]
 :outputs [{:name :frag-color :type :vec4 :location 0}]
 :push-constants [{:name :color :type :vec4}]
 :main [(set! gl-Position (vec4 (.x in-pos) (.y in-pos) 0.0 1.0))
        (set! frag-color (.color pc))]}
```

```clojure
(def my-vert (warpaint.dsl/load-edn "shaders/my-shader.vert.edn"))
```

## Compilation Backend

warpaint uses [lwjgl-shaderc](https://www.lwjgl.org/) for in-process GLSL → SPIR-V compilation with no subprocess overhead. If shaderc natives are unavailable on your platform, it falls back to invoking `glslc` as a subprocess (from `glslang-tools`).

Native classifiers are included for:
- Linux x86_64
- Linux aarch64 (Raspberry Pi, AWS Graviton)
- macOS Intel
- macOS Apple Silicon

## License

[CC0 1.0 Universal](https://creativecommons.org/publicdomain/zero/1.0/) — public domain.
