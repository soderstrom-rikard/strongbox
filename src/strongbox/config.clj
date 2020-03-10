(ns strongbox.config
  (:require
   [me.raynes.fs :as fs]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [strongbox
    [specs :as sp]
    [utils :as utils]]))

;; if the user provides their own catalogue list in their config file, it will override these defaults entirely
;; if the `:catalogue-source-list` entry is *missing* in the user config file, these will be used instead.
;; to use strongbox with no catalogues at all, use `:catalogue-source-list []` (empty list)
(def -default-catalogue-list
  [{:name :short :label "Short (default)" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/short-catalog.json"}
   {:name :full :label "Full" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/full-catalog.json"}
   ;; ---
   {:name :tukui :label "Tukui" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/tukui-catalog.json"}
   {:name :curseforge :label "Curseforge" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/curseforge-catalog.json"}
   {:name :wowinterface :label "WoWInterface" :source "https://raw.githubusercontent.com/ogri-la/wowman-data/master/wowinterface-catalog.json"}])

(def default-cfg
  {:addon-dir-list []
   :selected-catalogue :short
   :catalogue-source-list -default-catalogue-list
   :gui-theme :light})

(defn handle-install-dir
  "`:install-dir` was once supported in the user configuration but is now only supported in the command line options.
  this function will expand `:install-dir` to an 'addon-dir-map' if present and drop the `:install-dir` key"
  [cfg]
  (let [install-dir (:install-dir cfg)
        addon-dir-list (->> cfg :addon-dir-list (mapv :addon-dir))
        stub {:addon-dir install-dir :game-track "retail"}
        ;; add stub to addon-dir-list if install-dir isn't nil and doesn't match anything already present
        cfg (if (and install-dir
                     (not (utils/in? install-dir addon-dir-list)))
              (update-in cfg [:addon-dir-list] conj stub)
              cfg)]
    ;; finally, ensure :install-dir is absent from whatever we return
    (dissoc cfg :install-dir)))

(defn-spec valid-catalogue-source? boolean?
  "returns true if given `catalogue-source` is a valid `::sp/catalogue-source-map`"
  [catalogue-source any?]
  (let [v (s/valid? ::sp/catalogue-source-map catalogue-source)]
    (when-not v
      (warn "discarding catalogue source:" catalogue-source)
      (s/explain ::sp/catalogue-source-map catalogue-source))
    v))

(defn remove-invalid-catalogue-source-entries
  "invalid `:catalogue-source-list` entries are stripped"
  [cfg]
  (if-let [csl (:catalogue-source-list cfg)]
    (if (not (vector? csl))
      ;; we have something, but whatever we were given it wasn't a vector. non-starter
      (assoc cfg :catalogue-source-list [])

      ;; strip anything that isn't valid
      (assoc cfg :catalogue-source-list (filterv valid-catalogue-source? csl)))

    ;; key not present, return config as-is
    cfg))

(defn remove-non-existant-dirs
  "removes any `addon-dir-map` items from the given configuration whose directories do not exist"
  [cfg]
  (assoc cfg :addon-dir-list
         (filterv (comp fs/directory? :addon-dir) (:addon-dir-list cfg))))

(defn strip-unspecced-keys
  "removes any keys from the given configuration that are not in the spec"
  [cfg]
  ;;(spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer))
  ;; not as good as the above spec-tools/coerce approach, but:
  ;; * saves about 1.5MB of dependencies
  ;; * it wasn't doing validation, just stripping extra keys
  ;; * it wasn't doing any conforming of values (like strings to integers)
  ;; * it didn't support :opt(ional) keysets
  (select-keys cfg [:addon-dir-list :selected-catalogue :gui-theme :catalogue-source-list]))

(defn-spec -merge-with ::sp/user-config
  "merges `cfg-b` over `cfg-a`, returning the result if valid else `cfg-a`"
  [cfg-a map?, cfg-b map?, msg string?]
  (debug "loading config:" msg)
  (let [cfg (-> cfg-a
                (merge cfg-b)
                handle-install-dir
                remove-invalid-catalogue-source-entries
                remove-non-existant-dirs
                strip-unspecced-keys)
        message (format "configuration from %s is invalid and will be ignored: %s"
                        msg (s/explain-str ::sp/user-config cfg))]
    (if (s/valid? ::sp/user-config cfg)
      cfg
      (do (warn message) cfg-a))))

(defn-spec merge-config ::sp/user-config
  "merges the default user configuration with settings in the user config file and any CLI options"
  [file-opts map?, cli-opts map?]
  (-> default-cfg
      (-merge-with file-opts "user settings")
      (-merge-with cli-opts "command line options")))

;;

(defn -load-settings
  "returns a map of user configuration settings that can be merged over `core/state`"
  [cli-opts file-opts etag-db]
  (let [cfg (merge-config file-opts cli-opts)
        default-selected-addon-dir (->> cfg :addon-dir-list (map :addon-dir) first)]
    {:cfg cfg
     :cli-opts cli-opts
     :file-opts file-opts
     :etag-db etag-db
     :selected-addon-dir default-selected-addon-dir}))

(defn-spec load-settings-file map?
  "reads application settings from the given file.
  returns an empty map if file is missing or malformed."
  [cfg-file ::sp/file]
  (let [catalogue-source-list-transformer
        (fn [lst]
          (mapv #(update-in % [:name] keyword) lst))]
    (utils/load-json-file-safely cfg-file
                                 :no-file? #(do (warn "configuration file not found: " cfg-file) {})
                                 :bad-data? {}
                                 :transform-map {:selected-catalogue keyword
                                                 :gui-theme keyword
                                                 :catalogue-source-list catalogue-source-list-transformer})))

(defn-spec load-etag-db-file map?
  "reads etag database from given file.
  if file is missing or malformed, returns an empty map"
  [etag-db-file ::sp/file]
  (utils/load-json-file-safely etag-db-file :no-file? {} :bad-data? {}))

(defn load-settings
  "reads config files and returns a map of configuration settings that can be merged over `core/state`"
  [cli-opts cfg-file etag-db-file]
  (let [file-opts (load-settings-file cfg-file)
        etag-db (load-etag-db-file etag-db-file)]
    (-load-settings cli-opts file-opts etag-db)))

;;

(st/instrument)
