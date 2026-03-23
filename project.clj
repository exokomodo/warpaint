(defproject io.github.exokomodo/warpaint "0.1.0"
  :description "S-expression shader DSL for Clojure — emit GLSL 450, compile to SPIR-V"
  :url "https://github.com/exokomodo/warpaint"
  :license {:name "CC0 1.0 Universal"
            :url "https://creativecommons.org/publicdomain/zero/1.0/"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [org.clojure/tools.logging "1.3.0"]
                 ;; shaderc — in-process GLSL → SPIR-V compilation
                 [org.lwjgl/lwjgl-shaderc "3.3.4"]
                 ;; Natives — Linux aarch64 (Raspberry Pi, AWS Graviton)
                 [org.lwjgl/lwjgl-shaderc "3.3.4" :classifier "natives-linux-arm64"]
                 ;; Natives — Linux x86_64
                 [org.lwjgl/lwjgl-shaderc "3.3.4" :classifier "natives-linux"]
                 ;; Natives — macOS Intel
                 [org.lwjgl/lwjgl-shaderc "3.3.4" :classifier "natives-macos"]
                 ;; Natives — macOS Apple Silicon
                 [org.lwjgl/lwjgl-shaderc "3.3.4" :classifier "natives-macos-arm64"]]

  :source-paths ["src"]
  :test-paths ["test"]

  :profiles
  {:dev {:plugins [[lein-cljfmt "0.9.2"]]
         :dependencies [[org.slf4j/slf4j-simple "2.0.9"]]}}

  :deploy-repositories [["clojars" {:url "https://clojars.org/repo"
                                     :sign-releases false}]])
