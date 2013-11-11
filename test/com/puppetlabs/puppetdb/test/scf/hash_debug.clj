(ns com.puppetlabs.puppetdb.test.scf.hash-debug
  (:require [clojure.test :refer :all]
            [com.puppetlabs.puppetdb.scf.hash-debug :refer :all]
            [com.puppetlabs.puppetdb.scf.hash :as shash]
            [com.puppetlabs.puppetdb.examples :refer [catalogs]]
            [clj-time.core :as time]
            [com.puppetlabs.puppetdb.scf.storage :as store]
            [com.puppetlabs.puppetdb.testutils :as tu]
            [fs.core :as fs]
            [com.puppetlabs.puppetdb.fixtures :as fixt]
            [com.puppetlabs.cheshire :as json]
            [com.puppetlabs.utils :as utils]))

(defn persist-catalog
  "Adds the certname and full catalog to the database, returns the catalog map with
   the generated as as `:persisted-hash`"
  [{:keys [certname] :as catalog}]
  (store/add-certname! certname)
  (let [persisted-hash (store/add-catalog! catalog)]
    (store/associate-catalog-with-certname! persisted-hash certname (time/now))
    (assoc catalog :persisted-hash persisted-hash)))

(defn find-file
  "Finds files in `dir` with the given `suffix`. Useful for the debugging
   files that include a UUID in the prefix of the file name."
  [^String suffix dir]
  (first
   (for [f (fs/list-dir dir)
         :when (.endsWith f suffix)]
     (str dir "/" f))))

(def ^{:doc "Reads a catalog debugging clojure file from the file system."}
  slurp-clj
  (comp read-string slurp find-file))

(def ^{:doc "Reads/parses a JSON catalog debugging file from the file system."}
  slurp-json
  (comp json/parse-string slurp find-file))

(deftest debug-catalog-output
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))
            new-catalog (assoc-in (:basic catalogs)
                                  [:resources {:type "File"
                                               :title "/etc/foobar/bazv2"}]
                                  {:type "File"
                                   :title "/etc/foobar/bazv2"})
            new-hash (shash/catalog-similarity-hash new-catalog)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash new-catalog)
        (is (= 5 (count (fs/list-dir debug-dir))))

        (let [{old-edn-res :resources
               old-edn-edges :edges
               :as old-edn} (slurp-clj "old-catalog.edn" debug-dir)
              {new-edn-res :resources
               new-edn-eges :edges
               :as new-edn} (slurp-clj "new-catalog.edn" debug-dir)
              {old-json-res "resources"
               old-json-edges "edges"
               :as old-json} (slurp-json "old-catalog.json" debug-dir)
              {new-json-res "resources"
               new-json-edges "edges"
               :as new-json} (slurp-json "new-catalog.json" debug-dir)
              catalog-metadata (slurp-json "catalog-metadata.json" debug-dir)]

          (is (some #(= "/etc/foobar/bazv2" (:title %)) new-edn-res))
          (is (some #(= "/etc/foobar/bazv2" (get % "title")) new-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (get % "title")) old-json-res))
          (is (not-any? #(= "/etc/foobar/bazv2" (:title %)) old-edn-res))

          (is (seq old-edn-res))
          (is (seq old-edn-edges))
          (is (seq old-json-res))
          (is (seq old-json-edges))

          (are [metadata-key] (contains? catalog-metadata metadata-key)
               "java version"
               "new catalog hash"
               "old catalog hash"
               "database name"
               "database version")

          (are [metadata-key] (and (utils/string-contains? (:certname new-catalog)
                                                           (get catalog-metadata metadata-key))
                                   (.startsWith (get catalog-metadata metadata-key) debug-dir))
               "old catalog path - edn"
               "new catalog path - edn"
               "old catalog path - json"
               "new catalog path - json")

          (is (not= (get catalog-metadata "new catalog hash")
                    (get catalog-metadata "old catalog hash"))))))))

(deftest debug-catalog-output-filename-uniqueness
  (fixt/with-test-db
    (fn []
      (let [debug-dir (fs/absolute-path (tu/temp-dir))
            {:keys [persisted-hash] :as orig-catalog} (persist-catalog (:basic catalogs))

            new-catalog-1 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv2"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-1 (shash/catalog-similarity-hash new-catalog-1)

            new-catalog-2 (assoc-in (:basic catalogs)
                                    [:resources {:type "File" :title "/etc/foobar/bazv3"}]
                                    {:type       "File"
                                     :title      "/etc/foobar/bazv2"})
            new-hash-2 (shash/catalog-similarity-hash new-catalog-2)]

        (is (nil? (fs/list-dir debug-dir)))
        (debug-catalog debug-dir new-hash-1 new-catalog-1)
        (debug-catalog debug-dir new-hash-2 new-catalog-2)
        (is (= 10 (count (fs/list-dir debug-dir))))))))

