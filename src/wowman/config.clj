(ns wowman.config
  (:require
   [me.raynes.fs :as fs]
   [clojure.spec.alpha :as s]
   [orchestra.spec.test :as st]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [spec-tools.core :as spec-tools]
   [wowman
    [specs :as sp]
    [utils :as utils]]))

(def default-cfg
  {:addon-dir-list []
   :debug? false
   :selected-catalog :short})

(defn handle-install-dir
  "when `:install-dir` is present in given configuration, expands it to an addon-dir map and removes the value from the config
  `:install-dir` was once supported in the user configuration but is now only supported in the command line options.
  this function handles it's presence in both"
  [cfg]
  (let [install-dir (:install-dir cfg)
        addon-dir-list (->> cfg :addon-dir-list (map :addon-dir) vec)
        stub {:addon-dir install-dir :game-track "retail"}
        ;; add stub to addon-dir-list if install-dir isn't nil and doesn't match anything already present
        cfg (if (and install-dir
                     (not (utils/in? install-dir addon-dir-list)))
              (update-in cfg [:addon-dir-list] conj stub)
              cfg)]
      ;; finally, ensure :install-dir is absent from whatever we return
    (dissoc cfg :install-dir)))

(defn remove-non-existant-dirs
  "removes any addon directories from the given configuration that do not exist"
  [cfg]
  (assoc cfg :addon-dir-list
         (filterv (comp fs/directory? :addon-dir) (:addon-dir-list cfg))))

(defn strip-unspecced-keys
  "removes any keys from the given configuration that are not in the spec"
  [cfg]
  ;; doesn't support optional :opt keysets
  (spec-tools/coerce ::sp/user-config cfg spec-tools/strip-extra-keys-transformer))

(defn-spec configure-with ::sp/user-config
  [cfg-a map?, cfg-b map?, msg string?]
  (debug "loading config:" msg)
  (let [cfg (-> cfg-a
                (merge cfg-b)
                handle-install-dir
                remove-non-existant-dirs
                strip-unspecced-keys)
        message (format "configuration from %s is invalid and will be ignored: %s"
                        msg (s/explain-str ::sp/user-config cfg))]
    (if (s/valid? ::sp/user-config cfg)
      cfg
      (do (warn message) cfg-a))))

(defn-spec configure ::sp/user-config
  [file-opts map?, cli-opts map?]
  (-> default-cfg
      (configure-with file-opts "user settings")
      (configure-with cli-opts "command line options")))

(defn load-settings
  "returns a map that can be merged over the default state template"
  [cli-opts file-opts etag-db]
  (let [cfg (configure file-opts cli-opts)
        final-state {:cfg cfg,
                     :cli-opts cli-opts,
                     :file-opts file-opts,
                     :etag-db etag-db
                     :selected-addon-dir (->> cfg :addon-dir-list (map :addon-dir) first)}]

    final-state))

;;

(st/instrument)
