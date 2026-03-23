(ns warpaint.dsl-test
  "Unit tests for the warpaint DSL emitter.
   These only test GLSL string generation — no native shaderc required."
  (:require [clojure.test :refer [deftest is testing]]
            [warpaint.dsl :refer [emit-glsl]]))

(deftest emit-version-header
  (testing "always emits #version 450"
    (is (clojure.string/starts-with?
         (emit-glsl {:stage :vertex :main []})
         "#version 450"))))

(deftest emit-inputs
  (testing "single input"
    (let [glsl (emit-glsl {:inputs [{:name :in-pos :type :vec2 :location 0}]
                           :main []})]
      (is (clojure.string/includes? glsl "layout(location = 0) in vec2 inPos;"))))
  (testing "kebab-case name becomes camelCase"
    (let [glsl (emit-glsl {:inputs [{:name :frag-color :type :vec4 :location 1}]
                           :main []})]
      (is (clojure.string/includes? glsl "fragColor")))))

(deftest emit-outputs
  (testing "single output"
    (let [glsl (emit-glsl {:outputs [{:name :out-color :type :vec4 :location 0}]
                           :main []})]
      (is (clojure.string/includes? glsl "layout(location = 0) out vec4 outColor;")))))

(deftest emit-push-constants
  (testing "push constant block"
    (let [glsl (emit-glsl {:push-constants [{:name :translation :type :vec2}
                                            {:name :color :type :vec4}]
                           :main []})]
      (is (clojure.string/includes? glsl "layout(push_constant) uniform PushConstants {"))
      (is (clojure.string/includes? glsl "vec2 translation;"))
      (is (clojure.string/includes? glsl "vec4 color;"))
      (is (clojure.string/includes? glsl "} pc;")))))

(deftest emit-uniforms
  (testing "sampler uniform with set/binding"
    (let [glsl (emit-glsl {:uniforms [{:name :tex :type :sampler2D :set 0 :binding 1}]
                           :main []})]
      (is (clojure.string/includes?
           glsl "layout(set = 0, binding = 1) uniform sampler2D tex;")))))

(deftest emit-expressions
  (testing "arithmetic"
    (let [glsl (emit-glsl {:main ['(+ 1.0 2.0)]})]
      (is (clojure.string/includes? glsl "(1.0 + 2.0)"))))

  (testing "field access"
    (let [glsl (emit-glsl {:main ['(.x some-vec)]})]
      (is (clojure.string/includes? glsl "someVec.x"))))

  (testing "set! assignment"
    (let [glsl (emit-glsl {:main ['(set! gl-Position (vec4 0.0 0.0 0.0 1.0))]})]
      (is (clojure.string/includes? glsl "gl_Position = vec4(0.0, 0.0, 0.0, 1.0)"))))

  (testing "function call"
    (let [glsl (emit-glsl {:main ['(normalize some-vec)]})]
      (is (clojure.string/includes? glsl "normalize(someVec)"))))

  (testing "ternary if"
    (let [glsl (emit-glsl {:main ['(if (> x 0.0) 1.0 -1.0)]})]
      (is (clojure.string/includes? glsl "(x > 0.0) ? 1.0 : -1.0"))))

  (testing "comparison operators"
    (let [glsl (emit-glsl {:main ['(= a b)]})]
      (is (clojure.string/includes? glsl "(a == b)")))
    (let [glsl (emit-glsl {:main ['(not= a b)]})]
      (is (clojure.string/includes? glsl "(a != b)"))))

  (testing "aget array index"
    (let [glsl (emit-glsl {:main ['(aget positions 0)]})]
      (is (clojure.string/includes? glsl "positions[0]")))))

(deftest emit-gl-builtins
  (testing "gl-Position → gl_Position"
    (let [glsl (emit-glsl {:main ['(set! gl-Position (vec4 0.0 0.0 0.0 1.0))]})]
      (is (clojure.string/includes? glsl "gl_Position"))))
  (testing "gl-VertexIndex → gl_VertexIndex"
    (let [glsl (emit-glsl {:main ['(aget positions gl-VertexIndex)]})]
      (is (clojure.string/includes? glsl "gl_VertexIndex"))))
  (testing "gl-FragCoord → gl_FragCoord"
    (let [glsl (emit-glsl {:main ['(.xy gl-FragCoord)]})]
      (is (clojure.string/includes? glsl "gl_FragCoord.xy")))))

(deftest full-vertex-shader
  (testing "polygon vertex shader round-trips through emit-glsl"
    (let [descriptor
          {:stage :vertex
           :inputs  [{:name :in-pos :type :vec2 :location 0}]
           :outputs [{:name :frag-color :type :vec4 :location 0}]
           :push-constants [{:name :translation :type :vec2}
                            {:name :rotation    :type :float}
                            {:name :pad         :type :float}
                            {:name :color       :type :vec4}]
           :main ['(let [^float c       (cos (.rotation pc))
                         ^float s       (sin (.rotation pc))
                         ^vec2  rotated (vec2 (- (* (.x in-pos) c) (* (.y in-pos) s))
                                              (+ (* (.x in-pos) s) (* (.y in-pos) c)))
                         ^vec2  final   (+ rotated (.translation pc))]
                     (set! gl-Position (vec4 final 0.0 1.0))
                     (set! frag-color (.color pc)))]}
          glsl (emit-glsl descriptor)]
      (is (clojure.string/includes? glsl "#version 450"))
      (is (clojure.string/includes? glsl "layout(location = 0) in vec2 inPos;"))
      (is (clojure.string/includes? glsl "layout(location = 0) out vec4 fragColor;"))
      (is (clojure.string/includes? glsl "layout(push_constant) uniform PushConstants {"))
      (is (clojure.string/includes? glsl "void main() {"))
      (is (clojure.string/includes? glsl "cos(pc.rotation)"))
      (is (clojure.string/includes? glsl "gl_Position")))))
