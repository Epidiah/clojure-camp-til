(defproject camp.clojure/til "0.0.1"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [io.github.escherize/huff "0.2.12"]
                 [http-kit "2.9.0-alpha1"]
                 [ring/ring-defaults "0.5.0"]
                 [io.github.nextjournal/markdown "0.6.157"]
                 [datalevin "0.9.12"]]
  ;; Datalevin needs these.
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
             "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"])
