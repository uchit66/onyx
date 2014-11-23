(ns onyx.log.commands.submit-job
  (:require [clojure.core.async :refer [chan go >! <! close!]]
            [clojure.set :refer [union difference map-invert]]
            [clojure.data :refer [diff]]
            [onyx.extensions :as extensions]))

(defmethod extensions/apply-log-entry :submit-job
  [{:keys [args]} replica]
  (-> replica
      (update-in [:jobs] conj (:id args))
      (update-in [:jobs] vec)
      (assoc-in [:task-schedulers (:id args)] (:task-scheduler args))
      (assoc-in [:tasks (:id args)] (:tasks args))
      (assoc-in [:allocations (:id args)] {})))

(defmethod extensions/replica-diff :submit-job
  [{:keys [args]} old new]
  {:job (:id args)})

(defn job->n-peers [replica]
  (let [j (count (:jobs replica))
        p (count (:peers replica))
        min-peers (int (/ p j))
        r (/ min-peers j)
        n (numerator r)
        max-peers (inc min-peers)]
    (into {}
          (map-indexed
           (fn [i [job-id tasks]]
             {job-id (if (< i n) max-peers min-peers)})
           (:allocations replica)))))

(defmethod extensions/reactions :submit-job
  [entry old new diff peer-args]
  (cond (and (= (:job-scheduler old) :onyx.job-scheduler/greedy)
             (not (seq (:jobs old))))
        [{:fn :volunteer-for-task
          :args {:id (:id peer-args)}}]
        (= (:job-scheduler old) :onyx.job-scheduler/round-robin)
        42))

(defmethod extensions/fire-side-effects! :submit-job
  [entry old new diff state]
  state)

