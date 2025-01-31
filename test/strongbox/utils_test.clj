(ns strongbox.utils-test
  (:require
   ;;[taoensso.timbre :refer [debug info warn error spy]]
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox
    [utils :as utils :refer [join]]
    [constants :as constants]]
   [me.raynes.fs :as fs]))

(def ^:dynamic *temp-dir-path* "")

(defn tempdir-fixture
  "each test has a temporary dir available to it"
  [f]
  (binding [*temp-dir-path* (str (fs/temp-dir "strongbox.utils-test."))]
    (f)
    (fs/delete-dir *temp-dir-path*)))

(use-fixtures :once tempdir-fixture)

(deftest format-interface-version
  (testing "integer interface version converted to dot-notation correctly"
    (let [cases
          ["00000" "0.0.0"
           "10000" "1.0.0"
           "00100" "0.1.0"
           "00001" "0.0.1"

           ;; actual cases
           "00304" "0.3.4" ;; first pre-release
           "10000" "1.0.0" ;; first release
           "20001" "2.0.1" ;; Burning Crusade, Before The Storm
           "30002" "3.0.2" ;; WotLK, Echos of Doom
           "30008a" "3.0.8a" ;; 'a' ?? supported, but eh ...

            ;; ambiguous/broken cases
           "00010" "0.0.0"
           "01000" "0.0.0"
           "10100" "1.1.0" ;; ambiguous, also, 1.10.0, 10.1.0, 10.10.0
           "10123" "1.1.3" ;; last patch of 1.x, should be 1.12.3

           ;; no match, return nil
           "" nil
           "0" nil
           "00" nil
           "000" nil
           "0000" nil
           "a" nil
           "aaaaa" nil
           "!" nil
           "!!!!!" nil]]

      (doseq [[case expected] (partition 2 cases)]
        (testing (str "testing " case " expecting: " expected)
          (is (= expected (utils/interface-version-to-game-version case))))))))

(deftest semver-sort
  (testing "basic sort"
    (let [given ["1.2.3" "4.11.6" "4.2.0" "1.5.19" "1.5.5" "4.1.3" "1.2" "2.3.1" "10.5.5" "1.6.0" "1.2.3" "11.3.0" "1.2.3.4" "1.6.0-unstable" "1.6.0-aaaaaa"]
          ;; sort order for the '-something' cases are unspecified. depends entirely on input order
          expected '("1.2" "1.2.3" "1.2.3" "1.2.3.4" "1.5.5" "1.5.19" "1.6.0" "1.6.0-unstable" "1.6.0-aaaaaa" "2.3.1" "4.1.3" "4.2.0" "4.11.6" "10.5.5" "11.3.0")]
      (is (= expected (utils/sort-semver-strings given))))))

(deftest days-between-then-and-now
  (testing "the number of days between two dates"
    (java-time/with-clock (java-time/fixed-clock "2001-01-02T00:00:00Z")
      (is (= (utils/days-between-then-and-now "2001-01-01") 1)))))

(deftest file-older-than
  (testing "files whose modification times are older than N hours"
    (java-time/with-clock (java-time/fixed-clock "1970-01-01T02:00:00Z") ;; jan 1st 1970, 2 am
      (let [path (utils/join *temp-dir-path* "foo")]
        (try
          (fs/touch path 0) ;; created Jan 1st 1970
          (.setLastModified (fs/file path) 0) ;; modified Jan 1st 1970
          (is (utils/file-older-than path 1))
          (is (not (utils/file-older-than path 3)))
          (finally
            (fs/delete path)))))))

(deftest pad
  (let [cases [;;[[nil 2] nil] ;; must be a collection
               [[[] 0] []]
               [[[] 2] [nil nil]]
               [[[nil nil] 2] [nil nil]]
               [[[:foo :bar] 2] [:foo :bar]]
               [[[:foo] 2] [:foo nil]]
               [[[:foo :bar] 1] [:foo :bar]]]]
    (doseq [[[coll pad-amt] expected] cases]
      (testing (str "list is padded, case:" expected)
        (is (= expected (utils/pad coll pad-amt)))))))

(deftest nilable
  (let [cases [[nil nil]
               [[] nil]
               ['() nil]
               [{} nil]
               [false nil]
               ["" nil]
               ["      " nil]

               [1 1]
               [:foo :foo]
               [[1] [1]]
               [{:foo 1} {:foo 1}]]]
    (doseq [[given expected] cases]
      (testing (str "certain false-y values can be coerced to nil, case: " given)
        (is (= expected (utils/nilable given)))))))

(deftest named-regex-groups
  (let [cases [[[#"(.*)" [] "bar"] {}]
               [[#"(.*)" [:foo] "bar"] {:foo "bar"}]]]
    (doseq [[args expected] cases]
      (testing (str "pattern matches are extracted into a map correctly, case: " args)
        (is (= expected (apply utils/named-regex-groups args)))))))

(deftest replace-file-ext
  (let [cases [[["/path/to/foo.ext" ".json"] "/path/to/foo.json"]
               [["/path/to/foo.ext" "json"] "/path/to/foo.json"]
               [["foo.ext" ".json"] "foo.json"]
               [["foo.ext" "json"] "foo.json"]

               [["foo" ".json"] "foo.json"]]]
    (doseq [[[given given-ext] expected] cases]
      (testing (format "a file can have it's extension replaced, case: (%s %s)" given given-ext)
        (is (= expected (utils/replace-file-ext given given-ext)))))))

(deftest all
  (let [cases [[[nil] false]
               [[nil nil nil] false]
               [[false] false]
               [[false false false] false]
               [[nil false nil] false]
               [[false nil false] false]

               [[] true]
               [[""] true]
               [[0] true]
               [[1 2 3] true]

               [[1 2 nil] false]
               [[1 nil 3] false]
               [[false 2 3] false]]]
    (doseq [[given expected] cases]
      (testing (format "'all', case: (%s %s)" given expected)
        (is (= expected (utils/all given)))))))

(deftest any
  (let [cases [[[nil] false]
               [[nil nil nil] false]
               [[false] false]
               [[false false false] false]
               [[nil false nil] false]
               [[false nil false] false]

               [[] false] ;; different from 'and' behaviour
               [[""] true]
               [[0] true]
               [[1 2 3] true]

               [[1 2 nil] true]
               [[1 nil 3] true]
               [[false 2 3] true]]]
    (doseq [[given expected] cases]
      (testing (format "'any', case: (%s %s)" given expected)
        (is (= expected (utils/any given)))))))

(deftest drop-nils
  (let [cases [[{} [] {}]
               [{} [:foo] {}]
               [{} [nil] {}]
               [{} ["foo"] {}]

               [{:foo nil} [] {:foo nil}]
               [{:foo nil} [:foo] {}]
               [{:foo :bar} [:foo] {:foo :bar}]
               [{:foo :bar, :bar nil} [:foo] {:foo :bar, :bar nil}]
               [{:foo :bar, :bar nil} [:bar] {:foo :bar}]]]

    (doseq [[m fields expected] cases]
      (testing (format "'drop-nils', case: (%s %s => %s)" m fields expected)
        (is (= expected (utils/drop-nils m fields)))))))

(deftest safe-subs
  (let [cases [[[nil 0] nil]
               [[nil 1] nil]
               [[nil 99] nil]
               [["" 0] ""]
               [["" 1] ""]
               [["foo" 0] ""]
               [["foo" 1] "f"]
               [["foo" 100] "foo"]
               [["foo" -100] ""]]]
    (doseq [[[string max] expected] cases]
      (is (= expected (utils/safe-subs string max))))))

(deftest no-new-lines
  (let [cases [[nil nil]
               ["" ""]
               [" " " "]
               ["\n" " "]
               ["\r\n" " "]
               ["foo\nbar" "foo bar"]
               ["foo\r\nbar" "foo bar"]
               ["foo\nbar\r\nbaz" "foo bar baz"]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/no-new-lines given)) (format "failed given '%s'" given)))))

(deftest drop-idx
  (let [cases [[nil 0 nil]
               [[] nil []]

               [[] 0 []]
               [[] 1 []]
               [[] 1000000000000 []]

               [[1 2 3] 0 [2 3]]
               [[1 2 3] 1 [1 3]]
               [[1 2 3] 2 [1 2]]
               [[1 2 3] 3 [1 2 3]]
               [[1 2 3] 4 [1 2 3]]

               ;; error cases
               ;;[[] -1 []]
               ]]
    (doseq [[v idx expected] cases]
      (is (= expected (utils/drop-idx v idx))))))

(deftest game-version-to-game-track
  (let [cases [;; defaults for bad values
               ["" :retail]
               ["1" :retail]
               ["2" :retail]
               ["foo" :retail]

               ;; classic
               ["1." :classic]
               ["1.13.0" :classic]
               ["1.100.100" :classic]
               [constants/latest-classic-game-version :classic]

               ;; classic-tbc
               ["2." :classic-tbc]
               ["2.5.1" :classic-tbc]
               ["2.foo.bar" :classic-tbc]
               [constants/latest-classic-tbc-game-version :classic-tbc]

               ;; everything else
               ["3.0.2" :retail]
               ["4.3.0" :retail]
               ["5.0.4" :retail]
               ;; ...etc
               ["9.0.1" :retail]
               [constants/latest-retail-game-version :retail]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/game-version-to-game-track given))))))

(deftest csv-map
  (testing "singular"
    (let [header [:key :val]
          row ["foo" "bar"]
          expected [{:key "foo" :val "bar"}]]
      (is (= expected (utils/csv-map header row)))))

  (testing "asfd"
    (let [header [:key :val]
          row-list [["foo" "bar"] ["baz" "bup"]]
          expected [{:key "foo" :val "bar"} {:key "baz" :val "bup"}]]
      (is (= expected (apply utils/csv-map header row-list))))))

(deftest guess-game-track
  (testing ""
    (let [cases [[nil nil]
                 ["" nil]
                 ["foo" nil]
                 ["1.2.3" nil]

                 ;; classic-tbc
                 ["bcc" :classic-tbc]
                 ["classic-tbc" :classic-tbc]
                 ["1.2.3-classic-tbc" :classic-tbc]
                 ["1.2.3-classic-tbc-no-lib" :classic-tbc]
                 ["classic-tbc-no-lib" :classic-tbc]
                 ["classic-tbc.no-lib" :classic-tbc]
                 ["classic_tbc" :classic-tbc]
                 ["1.2.3_classic_tbc_no-lib" :classic-tbc]

                 ;; classic-tbc (edge cases)
                 ["beta-tbc" :classic-tbc]
                 ["beta-bc" :classic-tbc]
                 ["beta_tbc" :classic-tbc]
                 ["beta bc" :classic-tbc]
                 ["beta (tbc)" :classic-tbc]
                 ["beta (bc)" :classic-tbc]
                 ["beta (bcc)" :classic-tbc]
                 ["beta (tbc) 2.13" :classic-tbc]
                 ["beta (bcc) 3.24" :classic-tbc]

                 ;; 2021-06-02 `-bcc` appears to have been adopted by BigWigs packager.
                 ;; we'll probably see much more use of it now.
                 ;; classic-bcc
                 ["WeakAuras-3.4.2-bcc.zip" :classic-tbc]
                 ["classic-bcc" :classic-tbc]
                 ["1.2.3-classic-bcc" :classic-tbc]
                 ["1.2.3-classic-bcc-no-lib" :classic-tbc]
                 ["classic-bcc-no-lib" :classic-tbc]
                 ["classic-bcc.no-lib" :classic-tbc]
                 ["classic_bcc" :classic-tbc]
                 ["1.2.3_classic_bcc_no-lib" :classic-tbc]

                 ;; classic
                 ["classic" :classic]
                 ["vanilla" :classic]
                 ["1.2.3-classic" :classic]
                 ["1.2.3-classic-no-lib" :classic]
                 ["classic-no-lib" :classic]
                 ["classic.no-lib" :classic]
                 ["1.2.3_classic_no-lib" :classic]

                 ;; retail
                 ["retail" :retail]
                 ["mainline" :retail]
                 ["1.2.3-retail" :retail]
                 ["1.2.3-retail-no-lib" :retail]
                 ["retail-no-lib" :retail]
                 ["retail.no-lib" :retail]
                 ["1.2.3_retail_no-lib" :retail]

                 ;; case insensitivity
                 ["Mainline" :retail]
                 ["Retail" :retail]
                 ["Classic" :classic]
                 ["Vanilla" :classic]
                 ["Classic-TBC" :classic-tbc]

                 ;; priority (classic-tbc > classic > retail)
                 ["retail-classic-tbc-classic" :classic-tbc]
                 ["retail-classic-classic-tbc" :classic-tbc]
                 ["classic-classic-tbc" :classic-tbc]
                 ["retail-classic" :classic]]]

      (doseq [[given expected] cases]
        (is (= expected (utils/guess-game-track given)) (format "failed case: %s" given))))))

(deftest select-vals
  (let [given {:a 1 :b 2 :c 3 :d 4 :e nil}

        cases [[[:a] [1]]
               [[:a :b] [1 2]]
               [[:b :a] [2 1]]

               [[:a :b :c :d :e] [1 2 3 4 nil]]
               [[:a :b :c :d :e :f] [1 2 3 4 nil]]

               [[:foo :bar :baz] []]

               [{} []]]]
    (doseq [[ks expected] cases]
      (is (= expected (utils/select-vals given ks))))))

(deftest deep-merge
  (let [;; only associatives (`map?`) are merged, other collections are overwritten.
        expected {:foo {:bar {:baz [4], :bup :boo, :car #{:cadr}}}}
        m1 {:foo {:bar {:baz [1 2 3], :car #{:cdr}}}}
        m2 {:foo {:bar {:baz [4] :bup :boo, :car #{:cadr}}}}]
    (is (= expected (utils/deep-merge m1 m2)))))

(deftest rmv
  (is (= [] (utils/rmv nil nil)))
  (is (= [] (utils/rmv [] nil)))
  (is (= [] (utils/rmv [] :bar)))
  (is (= [:foo] (utils/rmv [:foo] :bar)))
  (is (= [] (utils/rmv [:foo] :foo))))

(deftest format-dt
  (let [cases [[nil ""]
               ["" ""]
               ["2000-12-31T23:30:00Z" "30 minutes ago"]
               ["2000-12-31T23:00:00Z" "1 hour ago"]
               ["2000-12-31T12:00:00Z" "12 hours ago"]
               ["2000-12-31T00:00:00Z" "1 day ago"]
               ["2000-12-01T00:00:00Z" "1 month ago"]
               ["2000-01-01T00:00:00Z" "1 year ago"]

               ;; weird that the sixth hour changes '12 months from now' to '1 year from now'
               ["2002-01-01T05:00:00Z" "12 months from now"]
               ["2002-01-01T06:00:00Z" "1 year from now"]]]
    (with-redefs [;; default date formatter uses `(now)` as it's reference point.
                  utils/*pretty-dt-printer* utils/-pretty-dt-printer-dummy]
      (doseq [[given expected] cases]
        (is (= expected (utils/format-dt given)))))))

(deftest base64-decode
  (let [cases [;; input should be URLEncoded first
               [nil nil]
               ["" ""]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/base64-decode given))))))

(deftest interface-version-to-game-track
  (let [cases [[10123 :classic]
               [20123 :classic-tbc]
               [30123 :retail] ;; for now

               ;; bad interface versions
               [0 nil]
               [1111 nil]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/interface-version-to-game-track given))))))

(deftest ltrim
  (let [cases [["" ""]
               [" " ""]
               ["  " ""]
               [" foo " "foo "]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/ltrim given " "))))))

(deftest rtrim
  (let [cases [["" ""]
               [" " ""]
               ["  " ""]
               [" foo " " foo"]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/rtrim given " "))))))

(deftest trim
  (let [cases [["" ""]
               [" " ""]
               ["  " ""]
               [" foo " "foo"]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/trim given " "))))))

(deftest published-before-classic?
  (let [cases [["2001" nil]
               ["2001-01-01" nil]
               ["2001-01-01T01:00" nil]
               ["2001-01-01T01:00:00Z" true]
               ["2019-08-25T23:59:59Z" true]
               [constants/release-of-wow-classic false]
               ["2019-08-26T00:00:01Z" false]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/published-before-classic? given))))))

(deftest just-in
  (let [cases [[nil [] nil]
               [nil [:foo :bar [:baz]] nil]
               [{} [] nil]
               [{:foo :bar} [:foo] nil]
               [{:foo :bar} [:foo [:foo]] nil]
               [{:foo :bar, :baz :bup} [:foo [:foo :baz]] nil]
               [{:foo {:bar :baz}} [:foo [:bar]] {:bar :baz}]
               [{:foo {:bar :baz, :bup :boo}} [:foo [:bar :bup :boop]] {:bar :baz, :bup :boo}]

               [{:foo []} [:foo [:bar]] nil]
               [{:foo ""} [:foo [:bar]] nil]]]
    (doseq [[m ks expected] cases]
      (is (= expected (utils/just-in m ks)), (str "failed case: " m ks)))))

(deftest source-map
  (let [cases [[nil {}]
               [{} {}]
               [{:foo "bar"} {}]
               [{:source "foo"} {:source "foo"}]
               [{:source "foo" :source-id "bar"} {:source "foo" :source-id "bar"}]
               [{:source "foo" :source-id "bar" :name "baz"} {:source "foo" :source-id "bar"}]]]
    (doseq [[given expected] cases]
      (is (= expected (utils/source-map given))))))

(deftest find-depth
  (let [cases [[{} 0]
               [{:foo []} 0]

               ;; we went down one level but not further
               [{:children {:foo :bar}} 1]
               [{:children [{:foo :bar}]} 1]

               [{:children [{:children [{:children nil}]}]} 3]
               [{:children [{:children [{:children :foo}]}]} 3]
               [{:children [{:children [{:children []}]}]} 3]]]

    (doseq [[given expected] cases]
      (is (= expected (utils/find-depth given 0))))))
