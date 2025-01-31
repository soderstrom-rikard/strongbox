(ns strongbox.db
  (:require
   [taoensso.tufte :as tufte :refer [p]]
   [clojure.string]
   [clojure.spec.alpha :as s]
   [orchestra.core :refer [defn-spec]]
   [taoensso.timbre :refer [log debug info warn error spy]]
   [strongbox
    [utils :as utils]
    [specs :as sp]])
  (:import
   [java.util.regex Pattern]))

(defn-spec put-many :addon/summary-list
  "adds all of the items from `doc-list` into the given `db`.
  this is a leftover from when the database was using the H2 rdbms but kept because I 
  like the separation it provides"
  [db vector?, doc-list :addon/summary-list]
  (into db (vec doc-list)))

;; matching

(defn-spec find-in-db :db/addon-catalogue-match
  "looks for `installed-addon` in the given `db`, matching `toc-key` to a `catalogue-key`.
  if a `toc-key` and `catalogue-key` are actually lists, then all the `toc-keys` must match the `catalogue-keys`"
  [db :addon/summary-list, installed-addon :db/installed-addon-or-import-stub, toc-keys :db/toc-keys, catalogue-keys :db/catalogue-keys]
  (let [;; [:source :source-id] => [:source :source-id], :name => [:name]
        catalogue-keys (if (vector? catalogue-keys) catalogue-keys [catalogue-keys])
        toc-keys (if (vector? toc-keys) toc-keys [toc-keys])

        ;; [:source :source-id] => ["curseforge" 12345], [:name] => ["foo"]
        arg-vals (mapv #(get installed-addon %) toc-keys)
        missing-args? (some nil? arg-vals)

        ;; there are cases where the installed-addon is missing an attribute to match on.
        ;; typically happened on the old `:alias` key that has since been replaced but we also have cases of missing `:title` values.
        _ (when missing-args?
            (debug "failed to find all values for db search, refusing to match against nil values. keys:" toc-keys "; vals:" arg-vals))

        ;; for each catalogue key, fetch the values and compare
        match? (fn [row]
                 (= arg-vals (mapv #(get row %) catalogue-keys)))

        results (if missing-args?
                  [] ;; don't look for 'nil', just skip with no results
                  (into [] (filter match?) db)) ;; todo: I think the `xform` parameter in this `into` form needs to be comped with `first`
        match (-> results first)]
    (when match
      {;; the relationship the match was made on: [[:source :source-id] [:source :source_id]]
       :idx [toc-keys catalogue-keys]
       ;; the values of the match: ["curseforge" "deadly-boss-mods"]
       :key arg-vals
       :installed-addon installed-addon
       :matched? (not (nil? match)) ;; todo: still used?
       :catalogue-match match})))

;; todo: can we do better than 'map?' now?
(defn-spec -find-first-in-db (s/or :match map?, :no-match nil?)
  "find a match for the given `installed-addon` in the database using a list of attributes in `match-on-list`.
  returns immediately when first match is found (does not check other joins in `match-on-list`)."
  [db :addon/summary-list, installed-addon :db/installed-addon-or-import-stub, match-on-list sequential?]
  (if (or (:ignore? installed-addon) ;; todo: should this check be done? db is a bit low level for this
          (empty? match-on-list))
    ;; either addon is being ignored, or,
    ;; we have exhausted all possibilities. not finding a match is ok.
    nil
    (let [[toc-keys catalogue-keys] (first match-on-list) ;; => [:name] or [:source-id :source]
          match (find-in-db db installed-addon toc-keys catalogue-keys)]
      (if-not match ;; recur
        (-find-first-in-db db installed-addon (rest match-on-list))
        match))))

;; todo: flesh out the 'match' and match-on-list specs.
(defn-spec -db-match-installed-addons-with-catalogue (s/coll-of (s/or :match map? :no-match :addon/toc))
  "for each installed addon, search the catalogue across multiple joins until a match is found.
  addons with no match return themselves"
  [db :addon/summary-list, installed-addon-list :addon/installed-list]
  (let [;; toc-key -> db-catalogue-key
        ;; most -> least desirable match
        ;; nest to search across multiple parameters
        match-on-list [[[:source :source-id] [:source :source-id]] ;; source+source-id, perfect case
                       [:source :name] ;; source+name, we have a source but no source-id (nfo v1 files)
                       [:name :name]
                       [:label :label]
                       [:dirname :label]] ;; dirname = label, eg ./AdiBags = AdiBags
        ]
    (for [installed-addon installed-addon-list]
      (or (-find-first-in-db db installed-addon match-on-list)
          installed-addon))))

;; querying

(defn-spec -addon-by-source-and-name :addon/summary-list
  "returns a list of addon summaries whose source and name match (exactly) the given `source` and `name`"
  [db :addon/summary-list, source :addon/source, name ::sp/name]
  (let [xf (filter #(and (= source (:source %))
                         (= name (:name %))))]
    (into [] xf db)))

(defn-spec -addon-by-name :addon/summary-list
  "returns a list of addon summaries whose name matches (exactly) the given `name`"
  [db :addon/summary-list, name ::sp/name]
  (let [xf (filter #(= name (:name %)))]
    (into [] xf db)))

(defn -search
  "returns a lazily fetched and paginated list of addon summaries.
  results are constructed using a `seque` that (somehow) bypasses chunking behaviour so our
  search never takes more than `cap` results.
  matches on `uin` are case insensitive.
  `filter-by` filters are applied before searching for `uin`."
  [db uin cap filter-by user-catalogue-idx]
  (let [constantly-true (constantly true)

        user-catalogue-filter (if (:user-catalogue filter-by)
                                (fn [row]
                                  (contains? user-catalogue-idx (utils/source-map row)))
                                constantly-true)

        host-filter (if-let [source-list (:source filter-by)]
                      (fn [row]
                        (utils/in? (:source row) source-list))
                      constantly-true)

        tag-set (:tag filter-by)
        tag-filter (if-not (empty? tag-set)
                     (fn [row]
                       (if-let [row-tag-set (set (:tag-list row))]
                         ;; if the addon contains *some* of the selected tags, include it
                         (some tag-set row-tag-set)))
                     constantly-true)

        db (->> db
                (filter user-catalogue-filter)
                (filter host-filter)
                (filter tag-filter))

        random-sample? (and (nil? uin)
                            (not (:user-catalogue filter-by)))]

    ;; no/empty input, do a random sample
    (if random-sample?
      (let [pct (->> db count (max 1) (/ 100) (* 0.6))]
        ;; decrement cap here so navigation for random search results is disabled
        [(take (dec cap) (random-sample pct db))])

      ;; else, search by input
      (let [uin (clojure.string/trim (or uin ""))
            ;; implementation taken from here:
            ;; - https://www.baeldung.com/java-case-insensitive-string-matching
            regex (Pattern/compile (Pattern/quote uin) Pattern/CASE_INSENSITIVE)
            match-fn (fn [row]
                       (p :p3/db-search-row
                          (or
                           (.find (.matcher regex (or (:label row) "")))
                           (.find (.matcher regex (or (:description row) ""))))))]
        (p :p3/db-partition
           (partition-all cap (seque 100 (filter match-fn db))))))))

;;

;; not specced because the results and argument lists may vary greatly
(defn stored-query
  "common queries we can call by keyword"
  [db query-kw & [arg-list]]
  (case query-kw
    :addon-by-source-and-name (-addon-by-source-and-name db (first arg-list) (second arg-list))
    :addon-by-name (-addon-by-name db (first arg-list))
    :search (-search db (first arg-list) (second arg-list) (nth arg-list 2) (nth arg-list 3))
    nil))
