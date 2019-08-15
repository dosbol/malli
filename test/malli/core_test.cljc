(ns malli.core-test
  (:require [clojure.test :refer [deftest testing is are]]
            [malli.core :as m]
            [malli.transform :as transform]
            #?@(:clj  [[clojure.edn]]
                :cljs [[cljs.reader]])))

(defn with-schema-forms [result]
  (some-> result
          (update :schema m/form)
          (update :problems (partial map #(update % :schema m/form)))))

(defn results= [& results]
  (apply = (map with-schema-forms results)))

(deftest expand-key-test
  (are [schema expected]
    (= expected (second (#'m/expand-key schema nil identity)))

    [:x int?] nil
    [:x {:optional true} int?] {:optional true}
    [:x {:optional false} int?] {:optional false}
    [[:opt :x] {:optional false} int?] {:optional true}
    [[:req :x] {:optional false} int?] {:optional false}
    [[:opt :x] {:optional true} int?] {:optional true}
    [[:req :x] {:optional true} int?] {:optional false}))

(deftest validation-test

  (testing "coercion"
    (is (= true
           (m/validate int? 1)
           (m/validate (m/schema int?) 1)
           ((m/validator int?) 1)
           ((m/validator (m/schema int?)) 1))))

  (testing "function schemas"
    (let [schema (m/schema int?)]

      (is (true? (m/validate schema 1)))
      (is (false? (m/validate schema "1")))
      (is (false? (m/validate schema [1])))

      (is (nil? (m/explain schema 1)))
      (is (results= {:schema schema
                     :value "1"
                     :problems [{:path [], :in [], :schema schema, :value "1"}]}
                    (m/explain schema "1")))

      (is (= 'int? (m/form schema)))))

  (testing "composite schemas"
    (let [schema (m/schema [:and int? [:or pos-int? neg-int?]])]

      (is (true? (m/validate schema 1)))
      (is (true? (m/validate schema -1)))
      (is (false? (m/validate schema 0)))
      (is (false? (m/validate schema "1")))
      (is (false? (m/validate schema [1])))

      (is (nil? (m/explain schema 1)))
      (is (results= {:schema schema,
                     :value 0,
                     :problems [{:path [2 1], :in [], :schema pos-int?, :value 0}
                                {:path [2 2], :in [], :schema neg-int?, :value 0}]}
                    (m/explain schema 0)))

      (is (= [:and 'int? [:or 'pos-int? 'neg-int?]] (m/form schema)))))

  (testing "comparator schemas"
    (let [schema (m/schema [:> 0])]

      (is (true? (m/validate schema 1)))
      (is (false? (m/validate schema 0)))
      (is (false? (m/validate schema "abba")))

      (is (nil? (m/explain schema 1)))
      (is (results= {:schema [:> 0], :value 0, :problems [{:path [], :in [], :schema [:> 0], :value 0}]}
                    (m/explain schema 0)))

      (is (= [:> 0] (m/form schema)))))

  (testing "enum schemas"
    (let [schema (m/schema [:enum 1 2])]

      (is (true? (m/validate schema 1)))
      (is (false? (m/validate schema 0)))
      (is (false? (m/validate schema "abba")))

      (is (nil? (m/explain schema 1)))
      (is (results= {:schema [:enum 1 2], :value 0, :problems [{:path [], :in [], :schema [:enum 1 2], :value 0}]}
                    (m/explain schema 0)))

      (is (= [:enum 1 2] (m/form schema)))))

  (testing "map schemas"
    (let [schema1 (m/schema
                    [:map
                     [:x boolean?]
                     [:y {:optional true} int?]
                     [:z {:optional false} string?]])
          schema2 (m/schema
                    [:map
                     [:x boolean?]
                     [[:opt :y] int?]
                     [[:req :z] string?]])
          valid {:x true, :y 1, :z "kikka"}
          invalid {:x true, :y "invalid", :z "kikka", :extra "ok"}]

      (doseq [schema [schema1 schema2]]
        (is (true? (m/validate schema valid)))
        (is (false? (m/validate schema invalid)))
        (is (false? (m/validate schema "not-a-map"))))

      (is (results= {:schema schema1
                     :value {:y "invalid" :z "kikka"}
                     :problems
                     [{:path [], :in [], :schema schema1, :type ::m/missing-key, ::m/key :x}
                      {:path [2 2], :in [:y], :schema int?, :value "invalid"}]}
                    (m/explain schema1 {:y "invalid" :z "kikka"})))

      (is (results= {:schema schema1
                     :value "not-a-map"
                     :problems [{:path [], :in [], :schema schema1, :value "not-a-map", :type ::m/invalid-type}]}
                    (m/explain schema1 "not-a-map")))

      (is (= [:map
              [:x 'boolean?]
              [:y {:optional true} 'int?]
              [:z {:optional false} 'string?]]
             (m/form schema1)
             (m/form schema2)))))

  (testing "sequence schemas"

    (testing "validation"
      (let [expectations {"vector" [[true [:vector int?] [1 2 3]]
                                    [false [:vector int?] [1 "2" 3]]
                                    [false [:vector int?] [nil]]
                                    [false [:vector int?] "invalid"]

                                    [true [:vector {:min 3} int?] [1 2 3]]
                                    [false [:vector {:min 4} int?] [1 2 3]]

                                    [true [:vector {:max 3} int?] [1 2 3]]
                                    [false [:vector {:max 2} int?] [1 2 3]]

                                    [true [:vector {:min 1, :max 3} int?] [1 2 3]]
                                    [false [:vector {:min 4, :max 4} int?] [1 2 3]]

                                    [false [:vector int?] '(1 2 3)]
                                    [false [:vector int?] #{1 2 3}]]

                          "list" [[true [:list int?] '(1 2 3)]
                                  [false [:list int?] '(1 "2" 3)]
                                  [false [:vector int?] '(nil)]
                                  [false [:list int?] "invalid"]

                                  [true [:list {:min 3} int?] '(1 2 3)]
                                  [false [:list {:min 4} int?] '(1 2 3)]

                                  [true [:list {:max 3} int?] '(1 2 3)]
                                  [false [:list {:max 2} int?] '(1 2 3)]

                                  [true [:list {:min 1, :max 3} int?] '(1 2 3)]
                                  [false [:list {:min 4, :max 4} int?] '(1 2 3)]

                                  [false [:list int?] [1 2 3]]
                                  [false [:list int?] #{1 2 3}]]

                          "set" [[true [:set int?] #{1 2 3}]
                                 [false [:set int?] #{1 "2" 3}]
                                 [false [:set int?] #{nil}]
                                 [false [:set int?] "invalid"]

                                 [true [:set {:min 3} int?] #{1 2 3}]
                                 [false [:set {:min 4} int?] #{1 2 3}]

                                 [true [:set {:max 3} int?] #{1 2 3}]
                                 [false [:set {:max 2} int?] #{1 2 3}]

                                 [true [:set {:min 1, :max 3} int?] #{1 2 3}]
                                 [false [:set {:min 4, :max 4} int?] #{1 2 3}]

                                 [false [:set int?] '(1 2 3)]
                                 [false [:set int?] [1 2 3]]]

                          "tuple" [[true [:tuple int?] [1]]
                                   [true [:tuple int? string?] [1 "2"]]
                                   [false [:tuple int?] ["1"]]
                                   [false [:tuple int?] [nil]]
                                   [false [:tuple int?] "invalid"]

                                   ;; ignored
                                   [true [:tuple {:min 3} int?] [1]]
                                   [true [:tuple {:min 4} int?] [1]]

                                   ;; ignored
                                   [true [:tuple {:max 3} int?] [1]]
                                   [true [:tuple {:max 2} int?] [1]]

                                   ;; ignored
                                   [true [:tuple {:min 1, :max 3} int?] [1]]
                                   [true [:tuple {:min 4, :max 4} int?] [1]]

                                   [false [:tuple int?] '(1)]
                                   [false [:tuple int?] #{1}]]}]

        (doseq [[name data] expectations
                [expected schema value] data]
          (testing name
            (is (= expected (m/validate schema value)))))))

    (testing "map-of schema"

      (is (true? (m/validate [:map-of string? int?] {"age" 18})))
      (is (true? (m/validate [:map-of keyword? int?] {:age 18})))
      (is (false? (m/validate [:map-of string? int?] {:age "18"})))
      (is (false? (m/validate [:map-of string? int?] 1)))

      (is (nil? (m/explain [:map-of string? int?] {"age" 18})))
      (is (results= {:schema   [:map-of string? int?],
                     :value    {:age 18},
                     :problems [{:path   [1],
                                 :in     [:age],
                                 :schema string?,
                                 :value  :age}]}
                    (m/explain [:map-of string? int?] {:age 18})))
      (is (results= {:schema   [:map-of string? int?],
                     :value    {:age "18"},
                     :problems [{:path   [1],
                                 :in     [:age],
                                 :schema string?,
                                 :value  :age}
                                {:path [2],
                                 :in [:age],
                                 :schema int?,
                                 :value "18"}]}
                    (m/explain [:map-of string? int?] {:age "18"}))))

    (testing "explain"
      (let [expectations {"vector" (let [schema [:vector {:min 2, :max 3} int?]]

                                     [[schema [1 2]
                                       nil]

                                      [schema 1
                                       {:schema schema
                                        :value 1
                                        :problems [{:path [], :in [], :type ::m/invalid-type, :schema schema, :value 1}]}]

                                      [schema [1]
                                       {:schema schema
                                        :value [1]
                                        :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value [1]}]}]

                                      [schema [1 2 3 4]
                                       {:schema schema
                                        :value [1 2 3 4]
                                        :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value [1 2 3 4]}]}]

                                      [schema [1 2 "3"]
                                       {:schema schema
                                        :value [1 2 "3"]
                                        :problems [{:path [2], :in [2], :schema int?, :value "3"}]}]])

                          "list" (let [schema [:list {:min 2, :max 3} int?]]

                                   [[schema '(1 2)
                                     nil]

                                    [schema 1
                                     {:schema schema
                                      :value 1
                                      :problems [{:path [], :in [], :type ::m/invalid-type, :schema schema, :value 1}]}]

                                    [schema '(1)
                                     {:schema schema
                                      :value '(1)
                                      :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value '(1)}]}]

                                    [schema '(1 2 3 4)
                                     {:schema schema
                                      :value '(1 2 3 4)
                                      :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value '(1 2 3 4)}]}]

                                    [schema '(1 2 "3")
                                     {:schema schema
                                      :value '(1 2 "3")
                                      :problems [{:path [2], :in [2], :schema int?, :value "3"}]}]])

                          "set" (let [schema [:set {:min 2, :max 3} int?]]

                                  [[schema #{1 2}
                                    nil]

                                   [schema 1
                                    {:schema schema
                                     :value 1
                                     :problems [{:path [], :in [], :type ::m/invalid-type, :schema schema, :value 1}]}]

                                   [schema #{1}
                                    {:schema schema
                                     :value #{1}
                                     :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value #{1}}]}]

                                   [schema #{1 2 3 4}
                                    {:schema schema
                                     :value #{1 2 3 4}
                                     :problems [{:path [], :in [], :type ::m/limits, :schema schema, :value #{1 2 3 4}}]}]

                                   [schema #{1 2 "3"}
                                    {:schema schema
                                     :value #{1 2 "3"}
                                     :problems [{:path [2], :in [0], :schema int?, :value "3"}]}]])

                          "tuple" (let [schema [:tuple int? string?]]

                                    [[schema [1 "2"]
                                      nil]

                                     [schema 1
                                      {:schema schema
                                       :value 1
                                       :problems [{:path [], :in [], :type ::m/invalid-type, :schema schema, :value 1}]}]

                                     [schema [1]
                                      {:schema schema
                                       :value [1]
                                       :problems [{:path [], :in [], :type ::m/tuple-size, :schema schema, :value [1]}]}]

                                     [schema [1 2]
                                      {:schema schema
                                       :value [1 2]
                                       :problems [{:path [2], :in [1], :schema string?, :value 2}]}]])}]

        (doseq [[name data] expectations
                [schema value expected] data]
          (testing name
            (is (results= expected (m/explain schema value)))))))))

(deftest path-with-properties-test
  (let [?path #(-> % :problems first :path)]

    (is (= [1] (?path (m/explain [:and int?] "2"))))
    (is (= [2] (?path (m/explain [:and {:name "int?"} int?] "2"))))

    (is (= [1] (?path (m/explain [:vector int?] ["2"]))))
    (is (= [2] (?path (m/explain [:vector {:name "int?"} [int?]] ["2"]))))

    (is (= [1] (?path (m/explain [:tuple int?] ["2"]))))
    (is (= [2] (?path (m/explain [:tuple {:name "int?"} [int?]] ["2"]))))

    (is (= [1 1] (?path (m/explain [:map [:x int?]] {:x "1"}))))
    (is (= [2 1] (?path (m/explain [:map {:name int?} [:x int?]] {:x "1"}))))
    (is (= [2 2] (?path (m/explain [:map {:name int?} [:x {:optional false} int?]] {:x "1"}))))))

(deftest properties-test
  (testing "properties can be set and retrieved"
    (let [properties {:title "kikka"}]
      (is (= properties
             (m/properties [:and properties int?])
             (m/properties [int? properties]))))))

(deftest round-trip-test
  (testing "schemas can be roundtripped"
    (let [schema (m/schema
                   [:map
                    [:x boolean?]
                    [[:opt :y] int?]
                    [:z string?]])
          schema' (-> schema
                      (m/form)
                      (pr-str)
                      (#?(:clj  clojure.edn/read-string,
                          :cljs cljs.reader/read-string))
                      (m/schema))
          valid {:x true, :y 1, :z "kikka"}]
      (is (= true
             (m/validate schema valid)
             (m/validate schema' valid)))
      (is (= (m/form schema) (m/form schema'))))))

(deftest custom-registry-test
  (let [registry (merge
                   m/comparator-registry
                   m/base-registry
                   {:int (m/fn-schema :int int?)
                    :string (m/fn-schema :string string?)})]
    (is (true? (m/validate [:or :int :string] 123 {:registry registry})))
    (is (false? (m/validate [:or :int :string] 'kikka {:registry registry})))))

(deftest transform-test
  (is (= 1 (m/transform int? "1" transform/string-transformer)))
  (is (= "1" (m/transform int? "1" transform/json-transformer))))
