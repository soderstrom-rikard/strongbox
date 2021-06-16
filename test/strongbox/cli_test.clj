(ns strongbox.cli-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [strongbox.ui.cli :as cli]
   [clj-http.fake :refer [with-global-fake-routes-in-isolation]]
   [strongbox
    [utils :as utils]
    [logging :as logging]
    [main :as main]
    [catalogue :as catalogue]
    [core :as core]]
   [me.raynes.fs :as fs :refer [with-cwd]]
   [taoensso.timbre :as timbre :refer [debug info warn error spy]]
   [strongbox.test-helper :as helper :refer [with-running-app fixture-path]]))

(use-fixtures :each helper/fixture-tempcwd)

(deftest action
  (let [expected "0 installed\n0 updates\n"]
    (with-running-app
      (testing "action func can accept a plain keyword"
        (is (= (with-out-str (cli/action :list-updates)) expected)))
      (testing "action func can accept a map of args"
        (is (= (with-out-str (cli/action {:action :list-updates})) expected))))))

(deftest shameless-coverage-bump
  (let [safe-actions [:???
                      :list :list-updates :update-all]]
    (with-running-app
      (doseq [action-kw safe-actions]
        (testing (str "cli: " action)
          (cli/action action-kw))))))

(deftest write-catalogue
  (with-running-app
    (let [full (core/find-catalogue-local-path :full)
          short (core/find-catalogue-local-path :short)

          curse (core/find-catalogue-local-path :curseforge)
          wowi (core/find-catalogue-local-path :wowinterface)
          tukui (core/find-catalogue-local-path :tukui)]

      ;; copy some fixtures
      (fs/copy (fixture-path "catalogue--v2--curseforge.json") curse)
      (fs/copy (fixture-path "catalogue--v2--wowinterface.json") wowi)
      (fs/copy (fixture-path "catalogue--v2--tukui.json") tukui)

      (cli/action :write-catalogue)

      (testing "full and shortened catalogues were written"
        (is (fs/exists? (core/find-catalogue-local-path :full)))
        (is (fs/exists? (core/find-catalogue-local-path :short))))

      (testing "each catalogue has one addon each"
        (is (= 3 (-> full catalogue/read-catalogue :total))))

      (testing "the short catalogue has just one addon in range"
        (is (= 1 (-> short catalogue/read-catalogue :total)))))))

(deftest search-db--empty-db
  (testing "an empty database can be searched from the CLI"
    (with-running-app
      (let [expected []]
        (cli/search "foo")
        (Thread/sleep 10)
        (is (= expected (cli/search-results)))))))

(deftest search-db
  (testing "a populated database can be searched from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}
          expected [{:download-count 9,
                     :game-track-list [:retail :classic],
                     :label "Chinchilla",
                     :name "chinchilla",
                     :source "github",
                     :source-id "Ravendwyr/Chinchilla",
                     :tag-list [],
                     :updated-date "2019-10-19T15:07:07Z",
                     :url "https://github.com/Ravendwyr/Chinchilla"}]]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (cli/search "chin")
          (Thread/sleep 10)
          (is (= expected (cli/search-results))))))))

(deftest search-db--random
  (testing "a populated database can be randomly searched from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}]
      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          ;; any catalogue with less than 60 (a magic number) items has >100% probability of being included.
          (cli/random-search)
          (Thread/sleep 50)
          (is (= (core/get-state :db)
                 (cli/search-results))))))))

(deftest search-db--navigate
  (testing "a populated database can be searched forwards and backwards from the CLI"
    (let [catalogue (slurp (fixture-path "catalogue--v2.json"))
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}}

          expected-page-1 [{:created-date "2019-04-13T15:23:09.397Z",
                            :description "A New Simple Percent",
                            :download-count 1034,
                            :label "A New Simple Percent",
                            :name "a-new-simple-percent",
                            :source "curseforge",
                            :source-id 319346,
                            :tag-list [:unit-frames],
                            :updated-date "2019-10-29T22:47:42.463Z",
                            :url "https://www.curseforge.com/wow/addons/a-new-simple-percent"}]

          expected-page-2 [{:download-count 9,
                            :game-track-list [:retail :classic],
                            :label "Chinchilla",
                            :name "chinchilla",
                            :source "github",
                            :source-id "Ravendwyr/Chinchilla",
                            :tag-list [],
                            :updated-date "2019-10-19T15:07:07Z",
                            :url "https://github.com/Ravendwyr/Chinchilla"}]

          no-results []]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (swap! core/state assoc-in [:search :results-per-page] 1)
          (cli/search "c")
          (Thread/sleep 100)
          (is (= 1 (count (cli/search-results))))
          (is (= expected-page-1 (cli/search-results)))
          (is (cli/search-has-next?))
          (cli/search-results-next-page)
          (is (= expected-page-2 (cli/search-results)))

          ;; with 1 result per-page and exactly 1 result on this page, there may be more results
          ;; but we can't know for definite without realising the next page of results.
          (is (cli/search-has-next?))
          (cli/search-results-next-page)
          ;; in this case, there wasn't. 
          (is (= no-results (cli/search-results)))

          ;; now walk backwards
          (is (cli/search-has-prev?))
          (cli/search-results-prev-page)
          (is (= expected-page-2 (cli/search-results)))

          (is (cli/search-has-prev?))
          (cli/search-results-prev-page)
          (is (= expected-page-1 (cli/search-results)))

          (is (not (cli/search-has-prev?))))))))

(deftest pin-addon
  (testing "an addon can be installed, selected and pinned to it's current installed version"
    (let [addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                 :source "curseforge" :source-id 1
                 :download-url "https://path/to/remote/addon.zip"
                 :game-track :classic
                 :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")}]
      (with-running-app
        (cli/set-addon-dir! (helper/install-dir))
        (core/install-addon addon)
        (core/load-installed-addons)

        (let [addon (first (core/get-state :installed-addon-list))]
          (is (= "1.2.3" (:installed-version addon)))
          (is (not (contains? addon :pinned-version))))

        (cli/select-addons)
        (is (= 1 (count (core/get-state :selected-addon-list))))
        (cli/pin)

        (let [addon (first (core/get-state :installed-addon-list))]
          (is (= "1.2.3" (:pinned-version addon))))))))

(deftest unpin-addon
  (testing "an addon can be installed, selected and un-pinned"
    (let [addon {:name "everyaddon-classic" :label "EveryAddon (Classic)" :version "1.2.3" :url "https://group.id/never/fetched"
                 :source "curseforge" :source-id 1
                 :download-url "https://path/to/remote/addon.zip"
                 :game-track :classic
                 :-testing-zipfile (fixture-path "everyaddon-classic--1-2-3.zip")
                 ;; yes! we can installed an addon that is pre-pinned.
                 :pinned-version "1.2.3"}]
      (with-running-app
        (cli/set-addon-dir! (helper/install-dir))
        (core/install-addon addon)
        (core/load-installed-addons)

        (let [addon (first (core/get-state :installed-addon-list))]
          (is (= "1.2.3" (:installed-version addon)))
          (is (= "1.2.3" (:pinned-version addon))))

        (cli/select-addons)
        (is (= 1 (count (core/get-state :selected-addon-list))))
        (cli/unpin)

        (let [addon (first (core/get-state :installed-addon-list))]
          (is (not (contains? addon :pinned-version))))))))

(deftest add-tab
  (testing "a generic tab can be created"
    (let [expected [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}]]
      (with-running-app
        (is (= [] (core/get-state :tab-list)))
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (is (= expected (core/get-state :tab-list)))))))

(deftest add-addon-tab
  (testing "an addon can be used to create a tab"
    (let [addon {:source "curseforge" :source-id 123 :label "Foo"}
          expected [{:closable? true, :label "Foo", :tab-data {:source "curseforge", :source-id 123}, :tab-id "foobar" :log-level :info}]]
      (with-running-app
        (with-redefs [strongbox.utils/unique-id (constantly "foobar")]
          (cli/add-addon-tab addon))
        (is (= expected (core/get-state :tab-list)))))))

(deftest remove-all-tabs
  (testing "all tabs can be removed at once"
    (let [tab-list [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}
                    {:tab-id "bar" :label "Bar!" :closable? true :log-level :info :tab-data {:dirname "EveryOtherAddon"}}]
          expected []]
      (with-running-app
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (cli/add-tab "bar" "Bar!" true {:dirname "EveryOtherAddon"})
        (is (= tab-list (core/get-state :tab-list)))
        (cli/remove-all-tabs)
        (is (= expected (core/get-state :tab-list)))))))

(deftest remove-tab-at-idx
  (testing "all tabs can be removed at once"
    (let [tab-list [{:tab-id "foo" :label "Foo!" :closable? false :log-level :info :tab-data {:dirname "EveryAddon"}}
                    {:tab-id "bar" :label "Bar!" :closable? true :log-level :info :tab-data {:dirname "EveryOtherAddon"}}
                    {:tab-id "baz" :label "Baz!" :closable? false :log-level :info :tab-data {:dirname "EveryAddonClassic"}}]
          expected [(first tab-list)
                    (last tab-list)]]
      (with-running-app
        (cli/add-tab "foo" "Foo!" false {:dirname "EveryAddon"})
        (cli/add-tab "bar" "Bar!" true {:dirname "EveryOtherAddon"})
        (cli/add-tab "baz" "Baz!" false {:dirname "EveryAddonClassic"})
        (is (= tab-list (core/get-state :tab-list)))
        (cli/remove-tab-at-idx 1)
        (is (= expected (core/get-state :tab-list)))))))

(deftest addon-num-log-level
  (with-running-app
    (is (zero? (cli/addon-num-log-level :warn "EveryAddon")))
    (is (zero? (cli/addon-num-log-level :error "EveryAddon")))

    (logging/addon-log {:dirname "EveryAddon"} :warn "awooga") ;; warn #1
    (logging/with-addon {:dirname "EveryAddon"}
      (timbre/warn "wooooooga")) ;; warn #2

    (is (= 2 (cli/addon-num-log-level :warn "EveryAddon")))
    (is (zero? (cli/addon-num-log-level :error "EveryAddon")))

    (logging/addon-log {:dirname "EveryAddon"} :error "AWOOGA!")
    (is (= 2 (cli/addon-num-log-level :warn "EveryAddon")))
    (is (= 1 (cli/addon-num-log-level :error "EveryAddon")))))

(deftest addon-has-log-level
  (with-running-app
    (is (false? (cli/addon-has-log-level? :warn "EveryAddon")))
    (logging/addon-log {:dirname "EveryAddon"} :warn "warning message")
    (is (true? (cli/addon-has-log-level? :warn "EveryAddon")))
    (is (false? (cli/addon-has-log-level? :error "EveryAddon")))))

(deftest import-addon--github
  (testing "user addon is successfully added to the user catalogue from just a github url"
    (let [every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")

          fake-routes {"https://api.github.com/repos/Aviana/HealComm/releases"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "github-repo-releases--aviana-healcomm.json"))})}

                       "https://api.github.com/repos/Aviana/HealComm/contents"
                       {:get (fn [req] {:status 200 :body "[]"})}

                       "https://github.com/Aviana/HealComm/releases/download/2.04/HealComm.zip"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url "https://github.com/Aviana/HealComm"

          install-dir (helper/install-dir)

          expected-addon-dir (utils/join install-dir "EveryAddon")

          expected-user-catalogue [{:tag-list [],
                                    :game-track-list [],
                                    :updated-date "2019-10-09T17:40:04Z",
                                    :name "healcomm",
                                    :source "github",
                                    :label "HealComm",
                                    :download-count 30946,
                                    :source-id "Aviana/HealComm",
                                    :url "https://github.com/Aviana/HealComm"}]]
      (with-running-app
        (core/set-addon-dir! install-dir)
        (with-global-fake-routes-in-isolation fake-routes
          (cli/import-addon user-url)

          ;; addon was found and added to user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file)))))

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir)))))))

(deftest import-addon--wowinterface
  (testing "user addon is successfully added to the user catalogue from just a github url"
    (let [install-dir (helper/install-dir)

          match {:url "https://www.wowinterface.com/downloads/info25079",
                 :name "rotation-master",
                 :label "Rotation Master",
                 :updated-date "2019-07-29T21:37:00Z",
                 :download-count 80,
                 :source "wowinterface"
                 :source-id 25079
                 :game-track-list [:retail]
                 :tag-list []}

          ;; a mush of the above (.nfo written during install) and the EveryAddon .toc file
          expected {:description "Does what no other addon does, slightly differently",
                    :dirname "EveryAddon",
                    :group-id "https://www.wowinterface.com/downloads/info25079",
                    :installed-game-track :retail,
                    :installed-version "1.2.3",
                    :interface-version 70000,
                    :label "EveryAddon 1.2.3",
                    :name "rotation-master",
                    :primary? true,
                    :source "wowinterface",
                    :source-id 25079}

          expected-addon-dir (utils/join install-dir "EveryAddon")
          expected-user-catalogue [match]

          catalogue (utils/to-json (catalogue/new-catalogue [match]))

          every-addon-zip-file (fixture-path "everyaddon--1-2-3.zip")
          fake-routes {"https://raw.githubusercontent.com/ogri-la/strongbox-catalogue/master/short-catalogue.json"
                       {:get (fn [req] {:status 200 :body catalogue})}

                       "https://api.mmoui.com/v3/game/WOW/filedetails/25079.json"
                       {:get (fn [req] {:status 200 :body (slurp (fixture-path "wowinterface-api--addon-details.json"))})}

                       "https://cdn.wowinterface.com/downloads/getfile.php?id=25079"
                       {:get (fn [req] {:status 200 :body (utils/file-to-lazy-byte-array every-addon-zip-file)})}}

          user-url (:url match)]

      (with-global-fake-routes-in-isolation fake-routes
        (with-running-app
          (core/set-addon-dir! install-dir)

          ;; one addon in the database
          (is (= [match] (core/get-state :db)))

          ;; user gives us this url, we find it and install it
          (cli/import-addon user-url)

          ;; addon was successfully download and installed
          (is (fs/exists? expected-addon-dir))

          ;; re-read install dir
          (core/load-installed-addons)

          ;; we expect our mushy set of .nfo and .toc data
          (is (= [expected] (core/get-state :installed-addon-list)))

          ;; and that the addon was added to the user catalogue
          (is (= expected-user-catalogue
                 (:addon-summary-list (catalogue/read-catalogue (core/paths :user-catalogue-file))))))))))
