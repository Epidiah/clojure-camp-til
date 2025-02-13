(defproject camp.clojure/til "0.0.1"
  :dependencies [[org.clojure/clojure "1.12.0"]
                 [io.github.escherize/huff "0.2.12"]
                 [http-kit "2.9.0-alpha1"]
                 [metosin/malli "0.17.0"]
                 [com.taoensso/tempel "1.0.0-RC1"]
                 ;; Make Tempel and Datalevin happy together
                 [com.taoensso/nippy "3.4.2"]
                 [com.taoensso/encore "3.134.0"]
                 ;;
                 [ring/ring-defaults "0.5.0"]
                 [io.github.nextjournal/markdown "0.6.157"]
                 [girouette "0.0.10"]
                 [girouette/processor "0.0.8"]
                 [datalevin "0.9.18"]]
  :profiles {:uberjar {:aot :all}}
  :main til.core
  ;; Datalevin needs these.
  :jvm-opts ["--add-opens=java.base/java.nio=ALL-UNNAMED"
             "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"])
