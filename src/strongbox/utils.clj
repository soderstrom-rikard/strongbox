(ns strongbox.utils
  (:require
   [strongbox
    [specs :as sp]
    [constants :as constants]]
   [clojure.java.shell]
   [clojure.string :refer [lower-case]]
   [clojure.java.io]
   [clojure.spec.alpha :as s]
   [clojure.pprint]
   [orchestra.core :refer [defn-spec]]
   [orchestra.spec.test :as st]
   [clojure.data.json]
   [me.raynes.fs :as fs]
   [slugify.core :as sluglib]
   [taoensso.timbre :refer [debug info warn error spy]]
   [java-time :as jt]
   [java-time.format])
  (:import
   [java.util Base64]
   [org.ocpsoft.prettytime.units Decade]
   [org.ocpsoft.prettytime PrettyTime]))

(defn repl-stack-element?
  [stack-element]
  (and (= "clojure.main$repl" (.getClassName  stack-element))
       (= "doInvoke"          (.getMethodName stack-element))))

(defn in-repl?
  []
  (let [current-stack-trace (.getStackTrace (Thread/currentThread))]
    (some repl-stack-element? current-stack-trace)))

(defn instrument
  "if `flag` is true, enables spec checking instrumentation, otherwise disables it."
  [flag]
  (if flag
    (do
      (st/instrument)
      (info "instrumentation is ON"))
    (do
      (st/unstrument)
      (info "instrumentation is OFF"))))

(defn-spec all boolean?
  "true if all items in `lst` are neither nil nor false"
  [lst sequential?]
  (every? identity lst))

(defn-spec any boolean?
  "false if any item in `lst` is either nil or false"
  [lst sequential?]
  ((complement not-any?) identity lst))

(defn nilable
  [x]
  (cond
    (nil? x) nil
    (false? x) nil
    (and (coll? x)
         (empty? x)) nil
    (and (string? x)
         (clojure.string/blank? x)) nil
    :else x))

(defn-spec pad coll?
  "given a collection, ensures there are at least pad-amt items in result. pad value is nil"
  [lst coll?, pad-amt int?]
  (let [lst-size (count lst)]
    (if (< lst-size pad-amt)
      (into lst (repeat (- pad-amt lst-size) nil))
      lst)))

(defn-spec kw2str (s/or :ok? string? :nil nil?)
  "returns the string version of the given keyword, if keyword is not nil"
  [kw (s/nilable keyword?)]
  (when kw
    (name kw)))

(defn-spec safe-to-delete? boolean?
  "predicate, returns `true` if given file is prefixed with given directory."
  [dir ::sp/extant-dir file ::sp/extant-file]
  (clojure.string/starts-with? file dir))

(defn-spec delete-file! (s/or :ok nil?, :failed ::sp/file)
  "returns `nil` if deleting `path` inside `dir` was successful. returns `path` if deleting it was unsuccessful."
  [dir ::sp/extant-dir, path ::sp/extant-file]
  ;; these checks are important despite `defn-spec` as instrumentation is disabled for release.
  (cond
    (not (fs/exists? dir)) (do (warn "directory does not exist:" dir) path) ;; app may not have been started yet
    (not (safe-to-delete? dir path)) (do (error (format "refusing to delete file. file was not rooted at %s" dir)) path)
    (not (fs/file? path)) (do (error (format "refusing to delete file. file is not a file! %s" path)) path)
    :else (try
            (fs/delete path)
            (when (fs/exists? path)
              path)
            (catch Exception uncaught-exception
              (error uncaught-exception (str "unexpected error attempting to delete file: " path))
              path))))

(defn-spec delete-many-files! nil?
  "deletes a list of files rooted in given directory"
  [dir ::sp/dir, regex ::sp/regex, file-type ::sp/short-string]
  (if-not (fs/exists? dir)
    (warn "directory does not exist:" dir) ;; app may not have been started yet
    (let [file-list (mapv str (fs/find-files dir regex))
          suspicious (remove (partial safe-to-delete? dir) file-list)
          alert #(warn "deleting file " %)]
      (if-not (empty? suspicious)
        ;; this is a programming error, not a user error. if there is a problem we don't want N-1 more problems
        (error (format "refusing to delete all files. files were found not rooted at %s" (count suspicious) dir))

        (if (empty? file-list)
          (info (format "no %s files to delete" file-type))
          (do
            (warn (format "deleting %s %s files" (count file-list) file-type))
            (dorun (map (juxt alert fs/delete) file-list))))))))

(defn-spec file-older-than boolean?
  [file ::sp/extant-file, hours pos-int?]
  (let [modtime (jt/instant (fs/mod-time file))
        now (java-time/instant)
        expiry-offset (jt/hours hours)
        expiry-date (jt/plus modtime expiry-offset)
        expired? (jt/before? expiry-date now)]
    (when expired?
      ;; too noisy even for :debug when nothing has expired
      (debug (format "path %s; modtime %s; expiry-offset %s; expiry-date %s; now %s; expired? %s" file modtime expiry-offset expiry-date now expired?)))
    expired?))

(defn-spec days-between-then-and-now int?
  [datestamp ::sp/inst]
  (let [then (java-time/local-date datestamp)
        now (java-time/local-date)]
    (.getDays (java-time/period then now))))

(comment "unused"
         (defn fmt-date
           ([dateobj]
            (fmt-date dateobj "yyyy-MM-dd'T'HH:mm:ss'Z'"))
           ([dateobj fmt]
            (.format (java.text.SimpleDateFormat. fmt) dateobj))))

(defn datestamp-now-ymd
  []
  (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.)))

(defn-spec todt ::sp/zoned-dt-obj
  "takes an ISO8901 string and returns a java.time.ZonedDateTime object. 
  these are needed to calculate durations"
  [dt ::sp/inst]
  (java-time/zoned-date-time (get java-time.format/predefined-formatters "iso-zoned-date-time") dt))

(defn-spec dt-before? boolean?
  "returns `true` if `date-1` happened before `date-2`"
  [date-1 ::sp/inst, date-2 ::sp/inst]
  (jt/before? (todt date-1) (todt date-2)))

(defn-spec published-before-classic? (s/or :ok boolean?, :error nil?)
  [dt-string (s/nilable ::sp/inst)]
  (try
    (boolean (some-> dt-string (dt-before? constants/release-of-wow-classic)))
    (catch RuntimeException e
      (if (= (.getMessage e) "Conversion failed")
        (warn (str "bad date: " dt-string)))
      nil)))

(def -pretty-dt-printer (doto (PrettyTime.)
                          (.removeUnit Decade)))

(def -pretty-dt-printer-dummy (doto (PrettyTime. (java-time/local-date constants/fake-date) (java-time/zone-id "UTC"))
                                (.removeUnit Decade)))

(def ^:dynamic *pretty-dt-printer* -pretty-dt-printer)

(defn-spec format-dt string?
  "returns a PrettyTime formatted datetime representation or an empty string"
  [val (s/or :ok ::sp/inst, :supported nil?, :gui-edge-case ::sp/empty-string)]
  ;; the `gui-edge-case` comes from converting nil values (crashes widgets) to empty strings (less crashy)
  (or (some->> val nilable todt (.format *pretty-dt-printer*)) ""))

(defn nav-map
  "wrapper around `get-in` that returns the map as-is if given `path` is empty"
  [m path]
  (if (empty? path)
    m
    ;; temporary, to shake out any bad lookups in state
    ;;(ensure (get-in m path) (str "path does not exist: " (clojure.string/join ", " path)))))
    (get-in m path)))

(defn-spec nav-map-fn fn?
  "given a map `m`, returns a function that accepts an optional path of keywords into that map"
  [m map?]
  (fn [& path]
    (nav-map m path)))

(defn pprint
  [x]
  (with-out-str (clojure.pprint/pprint x)))

(defn items
  [& lst]
  (vec (remove nil? (flatten lst))))

(defn-spec safe-subs (s/nilable string?)
  "similar to `subs` but can handle `nil` input and a `max` value larger than (or less than) length of given string `x`."
  [^String x (s/nilable string?), ^Integer maxval int?]
  (when x
    (subs x 0 (min (count x) (if (neg? maxval) 0 maxval)))))

(defn in?
  ([needle haystack]
   (not (nil? (some #{needle} haystack))))
  ([haystack]
   (fn [needle]
     (in? needle haystack))))

(defn-spec idx map?
  [list-of-maps ::sp/list-of-maps, key keyword?]
  (into {} (map (fn [row] {(get row key) row}) list-of-maps)))

(defn-spec to-json string?
  [x ::sp/anything]
  (with-out-str (clojure.data.json/pprint x :escape-slash false)))

(defn from-json*
  [x]
  (some-> x (clojure.data.json/read-str :key-fn keyword)))

(defn from-json
  [x & [msg]]
  (try
    (from-json* x)
    (catch Exception exc
      (error (format (or msg (str "failed to parse json: %s")) (.getMessage exc)))
      nil)))

(defn-spec dump-json-file ::sp/extant-file
  [path ::sp/file, data ::sp/anything]
  (spit path (to-json data))
  path)

(defn call-if-fn
  [x]
  (if (fn? x) (x) x))

(defn-spec load-json-file-safely (s/or :ok ::sp/anything, :error nil?)
  "loads json file at given path with handling for common error cases (no file, bad data, invalid data)
  if :invalid-data? given, then a :data-spec must also be given else nothing happens and you get nil back"
  ([path ::sp/file]
   (load-json-file-safely path {}))
  ([path (s/or :file ::sp/file, :bytes bytes?), opts map?]
   (let [{:keys [no-file? bad-data? invalid-data? data-spec value-fn key-fn transform-map]} opts
         default-key-fn keyword
         default-value-fn (fn [key val]
                            ((get transform-map key (constantly val)) val))
         ;; specific `key-fn` and `value-fn` take precendence over anything in `transform-map`
         value-fn (or value-fn default-value-fn)
         key-fn (or key-fn default-key-fn)]
     (if (and (not (bytes? path))
              (not (fs/file? path)))
       (call-if-fn no-file?)
       (let [data (try
                    (with-open [reader (clojure.java.io/reader path)]
                      (clojure.data.json/read reader :key-fn key-fn, :value-fn value-fn))
                    (catch Exception uncaught-exc
                      (warn uncaught-exc (format "failed to read data \"%s\" in file: %s" (.getMessage uncaught-exc) path))))]
         (cond
           (not data) (call-if-fn bad-data?)
           (and ;; both are present AND data is invalid
            (and invalid-data? data-spec)
            (not (s/valid? data-spec data))) (call-if-fn invalid-data?)
           :else data))))))

(defn-spec load-edn-file any?
  [path ::sp/extant-file]
  (-> path slurp read-string))

(defn load-edn-file-safely
  [path & {:keys [no-file? bad-data? invalid-data? data-spec]}]
  (if-not (fs/file? path)
    (call-if-fn no-file?)
    (let [data (load-edn-file path)]
      (cond
        (not data) (call-if-fn bad-data?)
        (and ;; both are present AND data is invalid
         (and invalid-data? data-spec)
         (not (s/valid? data-spec data))) (call-if-fn invalid-data?)
        :else data))))

(defn-spec to-int (s/or :ok int?, :error nil?)
  "given any value `x`, converts it to an integer or returns `nil` if it can't be converted."
  [x any?]
  (if (int? x)
    x
    (try (Integer/valueOf (str x))
         (catch NumberFormatException nfe
           nil))))

(defn-spec slugify string?
  [string string?]
  (sluglib/slugify string))

(defn-spec interface-version-to-game-version (s/or :ok string?, :no-match nil?)
  [iface-version (s/or :deprecated string?, :preferred ::sp/interface-version)]
  ;; warning! there is no way to convert *unambiguously* between the 'patch level' and the 'interface version'
  ;; for example, patch "1.2.0" => "10200", but so does "1.20.0" => "10200"
  ;; there haven't been any minor versions >4 since MOP
  ;; we'll hit 10.0 soon enough (we're at 8.x at time of writing) so what then? "10.0.1" => "10000" is another collision
  ;; the below code should only be considered unambigous for versions of WoW between 2.x and 8.x
  ;; (and 9.x if that series follows the behaviour of all other patch levels since 2.x)
  ;; see: https://wow.gamepedia.com/Patches
  (let [iface-regex #"(?<major>\d{1})\d(?<minor>\d{1})\d(?<patch>\d{1}\w?)"
        matcher (re-matcher iface-regex (str iface-version))
        major-minor-patch (rest (re-find matcher))]
    (when-not (empty? major-minor-patch)
      (clojure.string/join "." major-minor-patch))))

(defn-spec game-version-to-interface-version (s/or :ok ::sp/interface-version :error nil?)
  "'8.2.0' => '80200', '1.13.2' => '101300"
  [game-version string?]
  (let [;; patch-version isn't considered apparently: http://wowwiki.wikia.com/wiki/Getting_the_current_interface_number
        [major minor & _] (clojure.string/split game-version #"\.")
        major (to-int major)
        minor (to-int minor)]
    (when (and major minor)
      (+ (* 10000 major) (* 100 minor)))))

(defn-spec game-version-to-game-track ::sp/game-track
  "'1.13.2' => ':classic', '8.2.0' => 'retail'"
  [game-version string?]
  (let [prefix (safe-subs game-version 2)]
    (case prefix
      ;; 1.x.x == classic (vanilla)
      "1." :classic
      ;; 2.x.x == classic (burning crusade)
      "2." :classic-tbc
      ;; 3.x.x == classic (wrath of the lich king) (probably)
      :retail)))

(defn-spec interface-version-to-game-track (s/or :ok ::sp/game-track, :err nil?)
  "converts an interface version like '80000' to a game track like ':retail'"
  [interface-version ::sp/interface-version]
  (some-> interface-version
          interface-version-to-game-version
          game-version-to-game-track))

(defn-spec game-track-to-latest-game-version (s/or :ok string?, :err nil?)
  "':classic' => '1.13.0'"
  [game-track ::sp/game-track]
  (case game-track
    :retail constants/latest-retail-game-version
    :classic constants/latest-classic-game-version
    :classic-tbc constants/latest-classic-tbc-game-version))

;; https://stackoverflow.com/questions/13789092/length-of-the-first-line-in-an-utf-8-file-with-bom
(defn debomify
  [^String line]
  (let [bom "\uFEFF"]
    (if (.startsWith line bom)
      (.substring line 1)
      line)))

(defn de-bom-slurp ;; gurgle, fart, splat
  [x]
  (debomify (slurp x)))

(defn join
  [& args]
  (str (apply clojure.java.io/file args))) ;; (join "/foo" "bar" "baz") => /foo/bar/baz

(defn-spec ltrim string?
  "strips leading chars in `m` from `s`"
  [s string?, m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "^[%s]*" m))]
    (clojure.string/replace s pattern "")))

(defn-spec rtrim string?
  "strips trailing chars in `m` from `s`"
  [s string?, m string?]
  (let [pattern (java.util.regex.Pattern/compile (format "[%s]*$" m))]
    (clojure.string/replace s pattern "")))

(defn-spec trim string?
  "strips leading and trailing chars in `m` from `s`"
  [s string?, m string?]
  (-> s (ltrim m) (rtrim m)))

(defn-spec replace-file-ext (s/or :ok ::sp/file, :error nil?)
  [path ::sp/file, ext string?]
  (let [ext (ltrim ext ".")
        ext (str "." ext)
        ;; "foo" => nil, "foo/bar" => ["foo"], "/foo/bar" => ["/" "foo"]
        parent (some->> (-> path fs/split butlast) (apply join))]
    (join parent (-> path str fs/split-ext first (str ext)))))

(defn-spec file-to-lazy-byte-array bytes?
  [path ::sp/extant-file]
  (let [fobj (java.io.File. ^String path)
        ary (byte-array (.length fobj))
        is (java.io.FileInputStream. fobj)]
    (.read is ary)
    (.close is)
    ary))

(defn split-filter
  [f c]
  [(filterv f c) (filterv (complement f) c)])

(defn-spec cp ::sp/extant-file
  [old-path ::sp/extant-file new-dir ::sp/extant-dir]
  (let [new-path (join new-dir (fs/base-name old-path))]
    (fs/copy old-path new-path)
    new-path))

(defn -semver-comp
  [a-bits b-bits]
  (let [find-int (fn [x]
                   (or (some-> x first to-int)
                       ;; try again, this time ignore everything after any hyphen.
                       ;; if it's genuine bollocks we'll raise another exception
                       (some-> x first (clojure.string/split #"-") first to-int)))
        result (compare (find-int a-bits) (find-int b-bits))
        more? (not (empty? (rest b-bits)))]
    (if (= result 0)
      (if more?
        (-semver-comp (rest a-bits) (rest b-bits))
        result)
      result)))

(defn semver-comp
  [a-string b-string]
  (let [a-bits (clojure.string/split a-string #"\.")
        b-bits (clojure.string/split b-string #"\.")]
    (-semver-comp a-bits b-bits)))

(defn-spec sort-semver-strings (s/or :ok (s/coll-of string?) :empty empty?)
  "sort a list of semver strings with support for '1.2.3-something' suffixes."
  [semver-list (s/coll-of string?)]
  (sort semver-comp semver-list))

;; https://stackoverflow.com/questions/25892277/clojure-regex-named-groups#answer-25892938
(defn named-regex-groups
  "returns a map of values that were matched in the given regular expression using groups
  for example: (named-regex-groups #'(.*)' [:foo] 'bar') => {:foo 'bar'}"
  [regex groups value]
  (zipmap groups (rest (re-find regex value))))

(defn-spec unmangle-https-url (s/or :ok ::sp/url, :error nil?)
  "given something that is supposed to be a valid http URL, try our hardest to return an actual URL without actually visiting anything.
  if we fail, return nil, otherwise return a string that can be converted to a URL"
  [uin string?]
  (let [;; pretty strict, try this first
        url (try
              (java.net.URL. uin)
              (catch java.net.MalformedURLException _
                nil))

        ;; looser, but still better than a regex
        ^java.net.URI uri
        (try
          (java.net.URI. uin)
          (catch java.net.URISyntaxException _
            nil))

        ;; and finally, if url and uri approaches fail, try a quick and dirty regex
        groups [:protocol :lines :sub :host        :path]
        regex #"(\w*:)?(\/\/)?(www\.)?(.+\.\w{2,4})(/?.*)?"
        parsed (named-regex-groups regex groups uin)]

    (cond
      ;; woo! we have something valid already, return as-is
      (not (nil? url)) uin

      ;; ...woo? we have something with a host that isn't total garbage
      ;; try to recreate it, filling in a few blanks.
      (and (not (nil? uri))
           (.getHost uri)) (format "%s://%s%s"
                                   (or (.getScheme uri) "https")
                                   (.getHost uri)
                                   (or (.getRawPath uri) ""))

      (:host parsed) (format "%s://%s%s"
                             (or (:protocol parsed) "https")
                             (:host parsed)
                             (or (:path parsed) ""))

      ;; unparseable
      :else nil)))

;; https://clojure.atlassian.net/browse/CLJ-2007
(defmacro if-let*
  "Multiple binding version of if-let"
  ([bindings then]
   `(if-let ~bindings ~then nil))
  ([bindings then else]
   ;; assert-args is in clojure.core but private
   ;;(assert-args
   ;;  (vector? bindings) "a vector for its binding"
   ;;  (even? (count bindings)) "exactly even forms in binding vector")
   (if (== 2 (count bindings))
     `(let [temp# ~(second bindings)]
        (if temp#
          (let [~(first bindings) temp#]
            ~then)
          ~else))
     (let [if-let-else (keyword (name (gensym "if_let_else__")))
           inner (fn inner [bindings]
                   (if (seq bindings)
                     `(if-let [~(first bindings) ~(second bindings)]
                        ~(inner (drop 2 bindings))
                        ~if-let-else)
                     then))]
       `(let [temp# ~(inner bindings)]
          (if (= temp# ~if-let-else) ~else temp#))))))

;;

(defn-spec expand-path ::sp/file
  "given a path, expands any 'user' directories, relative directories and symbolic links"
  [path ::sp/file]
  (-> path fs/expand-home fs/normalized fs/absolute str))

(defn last-writeable-dir
  "given a path, returns the last writable directory or nil if no writable directory available"
  [path]
  (when path
    (if (and (fs/directory? path) (fs/writeable? path))
      (str path)
      (last-writeable-dir (fs/parent path)))))

(defn-spec drop-nils (s/or :ok map?, :empty nil?)
  "given a map `m` and a set of `fields`, if field is `nil`, `dissoc` it"
  [m map?, fields sequential?]
  (if (empty? fields)
    m
    (drop-nils
     (if (nil? (get m (first fields)))
       (dissoc m (first fields))
       m)
     (rest fields))))

(defn-spec copy-old-to-new-if-safe (s/or :copied ::sp/extant-file, :no-op nil?)
  "copies `old-path` to `new-path` if `old-path` exists and `new-path` doesn't exist"
  [old-path ::sp/file, new-path ::sp/file]
  (when (and (fs/exists? old-path)
             (not (fs/exists? new-path)))
    (fs/copy old-path new-path)))

;;

(defn-spec browser (s/or :ok fn? :error nil?)
  "given the name of a binary, returns a function that will open a given URL in a browser or nil if 
  the binary cannot be found."
  [bin string?]
  (try
    (when (->> bin (clojure.java.shell/sh "which") :exit (= 0))
      (fn [url]
        (info (format "opening URL with %s: %s" bin url))
        (clojure.java.shell/sh bin url)))
    (catch Exception uncaught-exception
      (error uncaught-exception "failed to call `which`"))))

(defn-spec java-browser (s/or :ok fn? :error nil?)
  "returns a function that will open a given value or nil if current Desktop is not supported.
  if the given value is a URL, we attempt to 'browse' it.
  if the given value is an extant directory, we attempt to 'open' it."
  []
  (let [desktop (java.awt.Desktop/getDesktop)]
    (when (and (java.awt.Desktop/isDesktopSupported)
               (.isSupported desktop java.awt.Desktop$Action/BROWSE)
               (.isSupported desktop java.awt.Desktop$Action/OPEN))
      (fn [path]
        (cond
          (s/valid? ::sp/url path) (do (info "opening URL:" path)
                                       (.browse desktop (java.net.URI. path)))
          (s/valid? ::sp/extant-dir path) (do (info "opening directory:" path)
                                              (.open desktop (java.io.File. ^String path)))
          :else (error "can't open: " path))))))

(defn-spec find-browser fn?
  "returns a function that attempts to open a given URL in a browser.
  Prints an error message to console if URL cannot be opened."
  []
  (let [xdg-open #(browser "xdg-open")
        gnome-open #(browser "gnome-open")
        kde-open #(browser "kde-open")
        fail (constantly #(error "failed to find a program to open URL:" (str %)))]
    (loop [lst [java-browser
                xdg-open gnome-open kde-open fail]]
      (if-let [browser-fn ((first lst))]
        browser-fn
        (recur (rest lst))))))

(defn-spec browse-to nil?
  "given a URL, open a browser window with it"
  [x (s/or :url ::sp/url, :dir ::sp/extant-dir)]
  (future
    ((find-browser) x))
  nil)

(defn-spec no-new-lines (s/or :ok string? :also-ok nil?)
  "removes all \n and \r\n from a string"
  [string (s/nilable string?)]
  (some-> string
          (clojure.string/replace "\r\n" " ")
          (clojure.string/replace "\n" " ")))

(defn-spec drop-idx (s/or :ok vector? :bad nil?)
  "removes element at index `idx` within vector `v`.
  if `v` is nil or empty, `v` will be returned as-is.
  if `idx` is negative or greater than the number of elements in `v`, `v` will be returned as-is."
  [v (s/nilable vector?), idx (s/nilable int?)]
  (when-not (nil? v)
    (let [c (count v)]
      (cond
        (nil? idx) v
        (empty? v) v
        (< idx 0) v ;; negative indices return the vector as-is
        (>= idx c) v ;; indicies greater than num items return vector as-is
        :else (into (subvec v 0 idx) (subvec v (inc idx) c))))))

(defn-spec extract-addon-id map?
  "attempts to extract a set of uniquely identifying attributes of the given `addon`.
  if it fails to find these attributes, the whole map will be returned as-is."
  [addon map?]
  (let [addon-id (select-keys addon [:source :source-id :dirname])]
    (if (not (empty? addon-id))
      addon-id
      addon)))

(defn-spec unique-id string?
  "returns a UUID as a string that is guaranteed to always be unique."
  []
  (str (java.util.UUID/randomUUID)))

(defn count-occurances
  ;; {"Foo-v1.zip" 1, "Foo-v2.zip 1, "Foo.zip" 5}
  [my-list my-key]
  (let [-count-occurances (fn [accumulator-m m]
                            (update accumulator-m (get m my-key) (fn [n] (inc (or n 0)))))]
    (reduce -count-occurances {} my-list)))

;; https://clojuredocs.org/clojure.core/zipmap#example-56fbf77de4b069b77203b858
(defn csv-map
  "ZipMaps header as keys and values from lines.
  usage: `(apply utils/csv-map [header row1 row2 rowN])`"
  [head & lines]
  (map #(zipmap (map keyword head) %1) lines))

(defn-spec guess-game-track (s/nilable ::sp/game-track)
  "returns the first game track it finds in the given string, preferring `:classic-tbc`, then `:classic`, then `:retail` (most to least specific).
  returns `nil` if no game track found."
  [string (s/nilable string?)]
  (when string
    (let [;; matches 'classic-tbc', 'classic-bc', 'classic-bcc', 'classic_tbc', 'classic_bc', 'classic_bcc', 'tbc', 'tbcc', 'bc', 'bcc'
          ;; but not 'classictbc' or 'classicbc' or 'classicbcc'
          ;; see tests.
          classic-tbc-regex #"(?i)classic[\W_]t?bcc?|[\W_]t?bcc?\W?|t?bcc?$"
          classic-regex #"(?i)classic|vanilla"
          retail-regex #"(?i)retail|mainline"]
      (cond
        (re-find classic-tbc-regex string) :classic-tbc
        (re-find classic-regex string) :classic
        (re-find retail-regex string) :retail))))

(defn-spec url-to-addon-source (s/or :known-source :addon/source, :unknown-source nil?)
  "returns the source of an addon for a given `url`"
  [url-str ::sp/url]
  (let [url-obj (-> url-str java.net.URL.)
        host (.getHost url-obj)
        host-sans-www (if (clojure.string/starts-with? host "www.")
                        (subs host 4)
                        host)]
    (case host-sans-www
      "gitlab.com" "gitlab"
      "github.com" "github"
      "wowinterface.com" "wowinterface"
      "curseforge.com" "curseforge"
      "tukui.org" (case (.getPath url-obj)
                    "/download.php" "tukui"
                    "/addons.php" "tukui"
                    "/classic-addons.php" "tukui-classic"
                    "/classic-tbc-addons.php" "tukui-classic-tbc"
                    nil)
      nil)))

(defn-spec message-list string?
  "returns a multi-line string with the given `msg` on top and each message in `msg-list` bulleted beneath it"
  [msg string?, msg-list ::sp/list-of-strings]
  (clojure.string/join (format "\n %s " constants/bullet) (into [msg] msg-list)))

(defn-spec reportable-error string?
  [msg string?]
  (message-list msg ["please report this! https://github.com/ogri-la/strongbox/issues"]))

(defn-spec select-vals coll?
  "like `get` on `m` but for each key in `ks`. removes nils."
  [m map?, ks (s/coll-of any?)]
  (remove #(= % :-missing) (map #(get m % :-missing) ks)))

;; https://github.com/unrelentingtech/clj-http-fake/blob/920630d21bbd9b3203c07bc458d4da1070fd6113/src/clj_http/fake.clj#L136
(let [byte-array-type (Class/forName "[B")]
  (defn byte-array?
    "Is `obj` a java byte array?"
    [obj]
    (instance? byte-array-type obj)))

(defn atom?
  "Is `obj` an atom?"
  [obj]
  (instance? clojure.lang.Atom obj))

(defn thread-pool-executor?
  [obj]
  (instance? java.util.concurrent.ThreadPoolExecutor obj))

;; https://gist.github.com/danielpcox/c70a8aa2c36766200a95#gistcomment-2759496-permalink
(defn deep-merge
  "merges `b` into `a` when `a` is a map, otherwise returns `b`.
  other collections are not considered."
  [a b]
  (if (map? a)
    (into a (for [[k v] b]
              [k (deep-merge (a k) v)]))
    b))

(defn rmv
  "removes element `x` from collection `coll`, returning a vector"
  [coll x]
  (into [] (remove #{x} coll)))

(defn select-keys*
  "same as `select-keys`, but with parameter order changed for expression threading."
  [ks m]
  (select-keys m ks))

(defn-spec base64-decode (s/or :ok? string?, :error nil?)
  [string (s/nilable string?)]
  (when string
    (String. (.decode (Base64/getDecoder) string))))

;; https://stackoverflow.com/questions/3407876/how-do-i-avoid-clojures-chunking-behavior-for-lazy-seqs-that-i-want-to-short-ci
(defn unchunk
  "wraps sequence `s` in a call to `lazy-seq` that produces a closure, avoiding chunking behaviour."
  [s]
  (when (seq s)
    (lazy-seq
     (cons (first s)
           (unchunk (next s))))))

(defn first-nn
  "returns the first non-nil value of lazily applying `f` to `lst`, avoiding chunking behaviour so side-effects are safe"
  [f lst]
  (->> lst
       unchunk
       (map f)
       (remove nil?)
       first))

(defn-spec github-url-to-source-id (s/or :ok :addon/source-id :error nil?)
  "extracts the addon ID from the given `url`."
  [url ::sp/url]
  (->> url java.net.URL. .getPath (re-matches #"^/([^/]+/[^/]+)[/]?.*") rest first))

(defn-spec just-in any?
  "like `get-in` but the last path element is used with `select-keys`.
  returns nil if the second-to-last path element doesn't yield a map."
  [m (s/nilable map?), ks vector?]
  (let [path (butlast ks)
        these-keys (last ks)]
    (when m
      (let [v (get-in m path)]
        (when (and (map? v) (vector? these-keys))
          (select-keys v these-keys))))))

(defn-spec source-map (s/nilable map?)
  [addon (s/nilable map?)]
  (select-keys addon [:source :source-id]))

(defn-spec find-depth int?
  "given a map `m`, if it contains `:children`, increments `i` and calls self"
  [m map?, i int?]
  (if (contains? m :children)
    (let [children (:children m)]
      (if (sequential? children)
        (apply max (conj (mapv #(find-depth % (inc i)) children) (inc i)))
        (inc i)))
    i))
