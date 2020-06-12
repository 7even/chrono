(ns chrono.io
  (:require [chrono.util :as util]
            [chrono.ops :as ops]
            [clojure.string :as str])
  (:import [java.time Instant OffsetDateTime ZoneOffset]
           java.util.Date)
  (:refer-clojure :exclude [format]))

(defn- format-str [v [fmt & fmt-args] lang]
  (if (and lang (contains? (util/locale lang) fmt))
    (let [short? (not (empty? (filter #(= % :short) fmt-args)))]
      (-> (util/locale lang)
          fmt
          (get-in [v (if short? :short :name)])))

    (let [width (or (first (filter integer? fmt-args)) (util/format-patterns fmt))]
      (cond->> (str v)
        width (util/pad-zero width)))))

(defn- internal-parse [s fmt strict?]
  (letfn [(match [f s] (-> (or (util/parse-patterns f) (util/sanitize f))
                           (as-> $ (str "(" $ ")" "(.+)?"))
                           re-pattern
                           (re-matches s)))
          (match-collection [process s f lang]
            (loop
                [[f & rest-f] f
                 s s
                 acc nil]
              (let [unit? (keyword? f)
                    [match? s rest-s] (process f s)
                    parsed-value (if unit? (util/parse-val s f lang))
                    parsed? (or parsed-value (not unit?))
                    acc (cond-> acc parsed-value (assoc f parsed-value))]
                (if (and parsed? (some? rest-s) (some? rest-f))
                  (recur rest-f rest-s acc)
                  [acc rest-f rest-s]))))]
    (let [lang (-> fmt meta ffirst)
          [acc rest-f rest-s] (match-collection match s fmt lang)]
      (when (or (not strict?)
                (and (nil? rest-s)
                     (nil? rest-f)))
        acc))))

(defn parse
  ([s] (parse s util/iso-fmt))
  ([s fmt]
   (when (string? s)
     (internal-parse s fmt false))))

(defn strict-parse
  ([s] (strict-parse s util/iso-fmt))
  ([s fmt] (internal-parse s fmt true)))

(defn format
  ([date-coll] (format date-coll util/iso-fmt))
  ([date-coll fmt-vec]
   (let [lang (-> fmt-vec meta ffirst)]
     (->> fmt-vec
          (mapv
           (fn [fmt]
             (let [fmt (cond-> fmt (not (vector? fmt)) vector)
                   f (first fmt)
                   v (get date-coll f)
                   format-fn (if (keyword? f)
                               (or (first (filter fn? fmt)) format-str)
                               (constantly (or v f)))]
               (format-fn (or v 0) fmt lang))))
          str/join))))

(defn date-convertable? [value in out]
  (ops/eq?
   (parse value in)
   (parse (format (parse value in) out) out)))

(defn date-valid? [value fmt]
  #?(:clj true ; TODO
     :cljs (not (js/isNaN (.parse js/Date (format (parse value fmt) [:year "-" :month "-" :day]))))))

(def epoch {:year 1970 :day 1 :month 1})

(defn from-epoch [e]
  (ops/plus {:sec e} epoch))

(defn to-epoch [date]
  (let [years (range (:year epoch) (:year date))
        months (range 1 (:month date))]
    (-> date
        (dissoc :year :month)
        (update :day #(reduce (fn [days year]
                                (+ days (if (util/leap-year? year) 366 365))) % years))
        (update :day #(reduce (fn [days month]
                                (+ days (util/days-in-month
                                         {:month month :year (:year date)}))) % months))
        util/seconds)))

(defn from-epoch2 [e]
  (let [d (Date. e)]
    {:year (+ 1900 (.getYear d))
     :month (inc (.getMonth d))
     :day (.getDate d)
     :hour (.getHours d)
     :min (.getMinutes d)
     :sec (.getSeconds d)}))

(defn to-epoch2 [{:keys [year month day hour min sec]}]
  (let [d (Date. (- year 1900)
                 (dec month)
                 day
                 hour
                 min
                 sec)]
    (.getTime d)))

(defn to-epoch3 [{:keys [year month day hour min sec ms tz]}]
  (let [offset (ZoneOffset/ofHours tz)
        dt (OffsetDateTime/of year month day hour min sec (* ms 1000000) offset)]
    {:seconds (.toEpochSecond dt)
     :nano (.getNano dt)
     :offset-hours tz}))

(defn from-epoch3 [{:keys [seconds nano offset-hours]}]
  (let [offset (ZoneOffset/ofHours offset-hours)
        inst (Instant/ofEpochSecond seconds nano)
        dt (OffsetDateTime/ofInstant inst offset)]
    {:year (.getYear dt)
     :month (.getMonthValue dt)
     :day (.getDayOfMonth dt)
     :hour (.getHour dt)
     :min (.getMinute dt)
     :sec (.getSecond dt)
     :ms (int (/ (.getNano dt) 1000000))
     :tz offset-hours}))
