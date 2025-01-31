(ns strongbox.tukui-api
  (:require
   [slugify.core :refer [slugify]]
   [clojure.spec.alpha :as s]
   [clojure.string :refer [lower-case]]
   [orchestra.core :refer [defn-spec]]
   ;;[taoensso.timbre :as log :refer [debug info warn error spy]]
   [strongbox
    [tags :as tags]
    [http :as http]
    [utils :as utils]
    [specs :as sp]]))

(def summary-list-url "https://www.tukui.org/api.php?addons")
(def classic-summary-list-url "https://www.tukui.org/api.php?classic-addons")
(def classic-tbc-summary-list-url "https://www.tukui.org/api.php?classic-tbc-addons")

(def proper-url "https://www.tukui.org/api.php?ui=%s")
(def tukui-proper-url (format proper-url "tukui"))
(def elvui-proper-url (format proper-url "elvui"))

(defn-spec make-url (s/nilable ::sp/url)
  "given a map of addon data, returns a URL to the addon's tukui page or `nil`"
  [{:keys [name source-id interface-version]} map?]
  (cond
    (not source-id) nil

    (and (neg? source-id) name)
    (str "https://www.tukui.org/download.php?ui=" name)

    (and (pos? source-id) interface-version)
    (case (utils/interface-version-to-game-track interface-version)
      :retail (str "https://www.tukui.org/addons.php?id=" source-id)
      :classic (str "https://www.tukui.org/classic-addons.php?id=" source-id)
      :classic-tbc (str "https://www.tukui.org/classic-tbc-addons.php?id=" source-id)
      nil)

    :else nil))

(defn-spec expand-summary (s/or :ok :addon/release-list, :error nil?)
  "fetches a list of releases from the addon host for the given `addon-summary`"
  [addon :addon/expandable, game-track ::sp/game-track]
  (let [source-id (:source-id addon)
        source-id-str (str source-id)

        url (cond
              (neg? source-id) (format proper-url (:name addon))
              (= game-track :retail) summary-list-url
              (= game-track :classic) classic-summary-list-url
              (= game-track :classic-tbc) classic-tbc-summary-list-url)

        ;; tukui addons do not share IDs across game tracks like curseforge does.
        ;; 2020-12-02: Tukui has dropped the per-addon endpoint, all results are now lists of items
        addon-list (some-> url http/download-with-backoff utils/nilable http/sink-error utils/from-json)
        addon-list (if (sequential? addon-list)
                     addon-list
                     (-> addon-list (update :id str) vector))

        ti (->> addon-list (filter #(= source-id-str (:id %))) first)

        interface-version (when-let [patch (:patch ti)]
                            {:interface-version (utils/game-version-to-interface-version patch)})]
    (when ti
      [(merge {:download-url (:url ti)
               :version (:version ti)
               :game-track game-track}
              interface-version)])))

;; catalogue building

(defn-spec tukui-date-to-rfc3339 ::sp/inst
  "convert a tukui-style datestamp into a mighty RFC3339 formatted one. assumes UTC."
  [tukui-dt string?]
  (let [[date time] (clojure.string/split tukui-dt #" ")]
    (if-not time
      (str date "T00:00:00Z") ;; tukui and elvui addons proper have no time component
      (str date "T" time "Z"))))

(defn-spec process-tukui-item :addon/summary
  "process an item from a tukui catalogue into an addon-summary. slightly different values by game-track."
  [tukui-item map?, game-track ::sp/game-track]
  (let [ti tukui-item
        ;; single case of an addon with no category :(
        ;; 'SkullFlower UI', source-id 143
        category-list (if-let [cat (:category ti)] [cat] [])
        addon-summary
        {:source (case game-track
                   :retail "tukui"
                   :classic "tukui-classic"
                   :classic-tbc "tukui-classic-tbc")
         :source-id (-> ti :id utils/to-int)

         ;; 2020-03: disabled in favour of :tag-list
         ;;:category-list category-list
         :tag-list (tags/category-list-to-tag-list "tukui" category-list)
         :download-count (-> ti :downloads utils/to-int)
         :game-track-list [game-track]
         :label (:name ti)
         :name (slugify (:name ti))
         :description (:small_desc ti)
         :updated-date (-> ti :lastupdate tukui-date-to-rfc3339)
         :url (:web_url ti)

         ;; both of these are available in the main download
         ;; however the catalogue is updated weekly and strongbox uses a mechanism of
         ;; checking each for updates rather than relying on the catalogue.
         ;; perhaps in the future when we scrape daily
         ;;:version (:version ti)
         ;;:download-url (:url ti)
         }]

    addon-summary))

(defn-spec -download-proper-summary :addon/summary
  "downloads either the elvui or tukui addon that exists separately and outside of the catalogue"
  [url ::sp/url]
  (let [game-track :retail ;; use retail catalogue
        addon-summary (-> url http/download-with-backoff utils/from-json (process-tukui-item game-track))]
    (assoc addon-summary :game-track-list [:classic :retail])))

(defn-spec download-elvui-summary :addon/summary
  "downloads the elvui addon that exists separately and outside of the catalogue"
  []
  (-download-proper-summary elvui-proper-url))

(defn-spec download-tukui-summary :addon/summary
  "downloads the tukui addon that exists separately and outside of the catalogue"
  []
  (-download-proper-summary tukui-proper-url))

(defn-spec download-retail-summaries :addon/summary-list
  "downloads and processes all items in the tukui 'live' (retail) catalogue"
  []
  (mapv #(process-tukui-item % :retail) (-> summary-list-url http/download-with-backoff utils/from-json)))

(defn-spec download-classic-summaries :addon/summary-list
  "downloads and processes all items in the tukui classic catalogue"
  []
  (mapv #(process-tukui-item % :classic) (-> classic-summary-list-url http/download-with-backoff utils/from-json)))

(defn-spec download-classic-tbc-summaries :addon/summary-list
  "downloads and processes all items in the tukui classic catalogue"
  []
  (mapv #(process-tukui-item % :classic-tbc) (-> classic-tbc-summary-list-url http/download-with-backoff utils/from-json)))

(defn-spec download-all-summaries :addon/summary-list
  "downloads and process all items from the tukui 'live' (retail) and classic catalogues"
  []
  (vec (concat (download-retail-summaries)
               (download-classic-summaries)
               (download-classic-tbc-summaries)
               [(download-tukui-summary)]
               [(download-elvui-summary)])))

(defn-spec parse-user-string (s/or :ok :addon/source-id, :error nil?)
  "extracts the addon ID from the given `url`, handling the edge cases of for retail tukui and elvui"
  [url ::sp/url]
  (let [[numeral string] (some->> url java.net.URL. .getQuery (re-find #"(?i)(?:id=(\d+)|ui=(tukui|elvui))") rest)]
    (if numeral
      (utils/to-int numeral)
      (case (-> string (or "") lower-case)
        "tukui" -1
        "elvui" -2
        nil))))
