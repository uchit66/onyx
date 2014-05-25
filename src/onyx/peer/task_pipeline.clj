(ns onyx.peer.task-pipeline
  (:require [clojure.core.async :refer [alts!! <!! >!! chan close! thread]]
            [com.stuartsierra.component :as component]
            [dire.core :as dire]
            [taoensso.timbre :as timbre]
            [onyx.coordinator.planning :refer [find-task]]
            [onyx.peer.pipeline-extensions :as p-ext]
            [onyx.peer.pipeline-internal-extensions :as internal-ext]
            [onyx.queue.hornetq :refer [hornetq]]
            [onyx.peer.transform :as transform]
            [onyx.peer.group :as group]
            [onyx.peer.aggregate :as aggregate]
            [onyx.extensions :as extensions]))

(defn create-tx-session [{:keys [queue]}]
  (extensions/create-tx-session queue))

(defn new-payload [sync peer-node payload-ch]
  (let [peer-contents (extensions/read-place sync peer-node)
        node (extensions/create sync :payload)
        updated-contents (assoc peer-contents :payload node)]
    (extensions/write-place sync peer-node updated-contents)
    node))

(defn munge-open-session [event session]
  (assoc event :session session))

(defn munge-read-batch [event]
  (let [rets (p-ext/read-batch event)]
    (merge event rets)))

(defn munge-decompress-batch [event]
  (let [rets (p-ext/decompress-batch event)]
    (merge event rets)))

(defn munge-strip-sentinel [event]
  (let [segments (:decompressed event)]
    (if (= (last segments) :done)
      (assoc event :tail-batch? true :decompressed (or (butlast segments) []))
      (assoc event :tail-batch? false))))

(defn munge-requeue-sentinel [{:keys [tail-batch?] :as event}]
  (if tail-batch?
    (let [rets (p-ext/requeue-sentinel event)]
      (merge event rets))
    event))

(defn munge-apply-fn [{:keys [decompressed] :as event}]
  (if (seq decompressed)
    (merge event (p-ext/apply-fn event))
    (merge event {:results []})))

(defn munge-compress-batch [event]
  (let [rets (p-ext/compress-batch event)]
    (merge event rets)))

(defn munge-write-batch [event]
  (let [rets (p-ext/write-batch event)]
    (merge event rets)))

(defn munge-status-check [{:keys [sync status-node] :as event}]
  (assoc event :commit? (extensions/place-exists? sync status-node)))

(defn munge-ack [{:keys [queue batch commit?] :as event}]
  (if commit?
    (let [rets (p-ext/ack-batch event)]
      (merge event rets))
    event))

(defn munge-commit-tx [{:keys [queue session commit?] :as event}]
  (if commit?
    (do (extensions/commit-tx queue session)
        (assoc event :committed true))
    event))

(defn munge-close-temporal-resources [event]
  (taoensso.timbre/info "Attempting temporal close")
  (internal-ext/close-temporal-resources event)
  (merge event (p-ext/close-temporal-resources event)))

(defn munge-close-resources [{:keys [queue session producers consumers reserve?] :as event}]
  (doseq [producer producers] (extensions/close-resource queue producer))
  (doseq [consumer consumers] (extensions/close-resource queue consumer))
  (when-not reserve?
    (extensions/close-resource queue session))
  (assoc event :closed? true))

(defn munge-new-payload [{:keys [sync peer-node peer-version payload-ch] :as event}]
  (if (= (extensions/version sync peer-node) peer-version)
    (let [node (new-payload sync peer-node payload-ch)]
      (extensions/on-change sync node #(>!! payload-ch %))
      (assoc event :new-payload-node node :completion? true))
    event))

(defn munge-seal-resource [{:keys [sync exhaust-node seal-node] :as event}]
  (let [seal-response-ch (chan)]
    (extensions/on-change sync seal-node #(>!! seal-response-ch %))
    (extensions/touch-place sync exhaust-node)
    (let [path (:path (<!! seal-response-ch))
          seal? (extensions/read-place sync path)]
      (if seal?
        (merge event (p-ext/seal-resource event))
        event))))

(defn munge-complete-task
  [{:keys [sync completion-node completion?] :as event}]
  (when completion?
    (extensions/touch-place sync completion-node))
  event)

(defn open-session-loop [read-ch kill-ch pipeline-data dead-ch]
  (loop []
    (when (first (alts!! [kill-ch] :default true))
      (when-let [session (create-tx-session pipeline-data)]
        (>!! read-ch (munge-open-session pipeline-data session))
        (recur)))))

(defn read-batch-loop [read-ch decompress-ch dead-ch]
  (loop []
    (when-let [event (<!! read-ch)]
      (>!! decompress-ch (munge-read-batch event))
      (recur))))

(defn decompress-batch-loop [decompress-ch strip-ch dead-ch]
  (loop []
    (when-let [event (<!! decompress-ch)]
      (>!! strip-ch (munge-decompress-batch event))
      (recur))))

(defn strip-sentinel-loop [strip-ch requeue-ch dead-ch]
  (loop []
    (when-let [event (<!! strip-ch)]
      (>!! requeue-ch (munge-strip-sentinel event))
      (recur))))

(defn requeue-sentinel-loop [requeue-ch apply-fn-ch dead-ch]
  (loop []
    (when-let [event (<!! requeue-ch)]
      (>!! apply-fn-ch (munge-requeue-sentinel event))
      (recur))))

(defn apply-fn-loop [apply-fn-ch compress-ch dead-ch]
  (loop []
    (when-let [event (<!! apply-fn-ch)]
      (>!! compress-ch (munge-apply-fn event))
      (recur))))

(defn compress-batch-loop [compress-ch write-batch-ch dead-ch]
  (loop []
    (when-let [event (<!! compress-ch)]
      (>!! write-batch-ch (munge-compress-batch event))
      (recur))))

(defn write-batch-loop [write-ch status-check-ch dead-ch]
  (loop []
    (when-let [event (<!! write-ch)]
      (>!! status-check-ch (munge-write-batch event))
      (recur))))

(defn status-check-loop [status-ch ack-ch dead-ch]
  (loop []
    (when-let [event (<!! status-ch)]
      (>!! ack-ch (munge-status-check event))
      (recur))))

(defn ack-loop [ack-ch commit-ch dead-ch]
  (loop []
    (when-let [event (<!! ack-ch)]
      (>!! commit-ch (munge-ack event))
      (recur))))

(defn commit-tx-loop [commit-ch close-resources-ch dead-ch]
  (loop []
    (when-let [event (<!! commit-ch)]
      (>!! close-resources-ch (munge-commit-tx event))
      (recur))))

(defn close-resources-loop [close-ch close-temporal-ch dead-ch]
  (loop []
    (when-let [event (<!! close-ch)]
      (>!! close-temporal-ch (munge-close-resources event))
      (recur))))

(defn close-temporal-loop [close-temporal-ch reset-payload-ch dead-ch]
  (loop []
    (when-let [event (<!! close-temporal-ch)]
      (>!! reset-payload-ch (munge-close-temporal-resources event))
      (recur))))

(defn reset-payload-node-loop [reset-ch seal-ch dead-ch]
  (loop []
    (when-let [event (<!! reset-ch)]
      (if (and (:tail-batch? event) (:commit? event))
        (let [event (munge-new-payload event)]
          (>!! seal-ch event))
        (>!! seal-ch event))
      (recur))))

(defn seal-resource-loop [seal-ch internal-complete-ch dead-ch]
  (loop []
    (when-let [event (<!! seal-ch)]
      (if (:completion? event)
        (>!! internal-complete-ch (munge-seal-resource event))
        (>!! internal-complete-ch event))
      (recur))))

(defn complete-task-loop [complete-ch dead-ch]
  (loop []
    (when-let [event (<!! complete-ch)]
      (when (and (:tail-batch? event) (:commit? event))
        (munge-complete-task event)
        (when (:completion? event)
          (>!! (:complete-ch event) :task-completed)))
      (recur))))

(defrecord TaskPipeline [id payload sync queue payload-ch complete-ch fn-params]
  component/Lifecycle

  (start [component]
    (taoensso.timbre/info "Starting Task Pipeline for" (:task/name (:task payload)))

    (let [open-session-kill-ch (chan 0)
          read-batch-ch (chan 0)
          decompress-batch-ch (chan 0)
          strip-sentinel-ch (chan 0)
          requeue-sentinel-ch (chan 0)
          apply-fn-ch (chan 0)
          compress-batch-ch (chan 0)
          write-batch-ch (chan 0)
          status-check-ch (chan 0)
          ack-ch (chan 0)
          commit-tx-ch (chan 0)
          close-resources-ch (chan 0)
          close-temporal-ch (chan 0)
          reset-payload-node-ch (chan 0)
          seal-ch (chan 0)
          complete-task-ch (chan 0)

          open-session-dead-ch (chan)
          read-batch-dead-ch (chan)
          decompress-batch-dead-ch (chan)
          strip-sentinel-dead-ch (chan)
          requeue-sentinel-dead-ch (chan)
          apply-fn-dead-ch (chan)
          compress-batch-dead-ch (chan)
          write-batch-dead-ch (chan)
          status-check-dead-ch (chan)
          ack-dead-ch (chan)
          commit-tx-dead-ch (chan)
          close-resources-dead-ch (chan)
          close-temporal-dead-ch (chan)
          reset-payload-node-dead-ch (chan)
          seal-dead-ch (chan)
          complete-task-dead-ch (chan)

          task (:task/name (:task payload))
          catalog (read-string (extensions/read-place sync (:catalog (:nodes payload))))

          pipeline-data {:id id
                         :task task
                         :catalog catalog
                         :task-map (find-task catalog task)
                         :ingress-queues (:task/ingress-queues (:task payload))
                         :egress-queues (:task/egress-queues (:task payload))
                         :peer-node (:peer (:nodes payload))
                         :status-node (:status (:nodes payload))
                         :exhaust-node (:exhaust (:nodes payload))
                         :seal-node (:seal (:nodes payload))
                         :completion-node (:completion (:nodes payload))
                         :workflow (read-string (extensions/read-place sync (:workflow (:nodes payload))))
                         :peer-version (extensions/version sync (:peer (:nodes payload)))
                         :payload-ch payload-ch
                         :complete-ch complete-ch
                         :params (or (get fn-params task) [])
                         :queue queue
                         :sync sync}
          
          pipeline-data (merge pipeline-data
                               (internal-ext/inject-pipeline-resources pipeline-data)
                               (p-ext/inject-pipeline-resources pipeline-data))]

      (dire/with-handler! #'open-session-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'read-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'decompress-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'strip-sentinel-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'requeue-sentinel-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'apply-fn-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'compress-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'write-batch-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'status-check-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'ack-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'commit-tx-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'close-resources-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))
      
      (dire/with-handler! #'close-temporal-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'reset-payload-node-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'seal-resource-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-handler! #'complete-task-loop
        java.lang.Exception
        (fn [e & _] (.printStackTrace e)))

      (dire/with-finally! #'open-session-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'read-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'decompress-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'strip-sentinel-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'requeue-sentinel-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'apply-fn-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'compress-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'write-batch-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'status-check-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'ack-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'commit-tx-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'close-resources-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'close-temporal-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'reset-payload-node-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'seal-resource-loop
        (fn [& args]
          (>!! (last args) true)))

      (dire/with-finally! #'complete-task-loop
        (fn [& args]
          (>!! (last args) true)))

      (assoc component
        :open-session-kill-ch open-session-kill-ch
        :read-batch-ch read-batch-ch
        :decompress-batch-ch decompress-batch-ch
        :strip-sentinel-ch strip-sentinel-ch
        :requeue-sentinel-ch requeue-sentinel-ch
        :apply-fn-ch apply-fn-ch
        :compress-batch-ch compress-batch-ch
        :write-batch-ch write-batch-ch
        :status-check-ch status-check-ch
        :ack-ch ack-ch
        :commit-tx-ch commit-tx-ch
        :close-resources-ch close-resources-ch
        :close-temporal-ch close-temporal-ch
        :reset-payload-node-ch reset-payload-node-ch
        :seal-ch seal-ch
        :complete-task-ch complete-task-ch

        :open-session-dead-ch open-session-dead-ch
        :read-batch-dead-ch read-batch-dead-ch
        :decompress-batch-dead-ch decompress-batch-dead-ch
        :strip-sentinel-dead-ch strip-sentinel-dead-ch
        :requeue-sentinel-dead-ch requeue-sentinel-dead-ch
        :apply-fn-dead-ch apply-fn-dead-ch
        :compress-batch-dead-ch compress-batch-dead-ch
        :write-batch-dead-ch write-batch-dead-ch
        :status-check-dead-ch status-check-dead-ch
        :ack-dead-ch ack-dead-ch
        :commit-tx-dead-ch commit-tx-dead-ch
        :close-resources-dead-ch close-resources-dead-ch
        :close-temporal-dead-ch close-temporal-dead-ch
        :reset-payload-node-dead-ch reset-payload-node-dead-ch
        :seal-dead-ch seal-dead-ch
        :complete-task-dead-ch complete-task-dead-ch
        
        :open-session-loop (thread (open-session-loop read-batch-ch open-session-kill-ch pipeline-data open-session-dead-ch))
        :read-batch-loop (thread (read-batch-loop read-batch-ch decompress-batch-ch read-batch-dead-ch))
        :decompress-batch-loop (thread (decompress-batch-loop decompress-batch-ch strip-sentinel-ch decompress-batch-dead-ch))
        :strip-sentinel-loop (thread (strip-sentinel-loop strip-sentinel-ch requeue-sentinel-ch strip-sentinel-dead-ch))
        :requeue-sentinel-loop (thread (requeue-sentinel-loop requeue-sentinel-ch apply-fn-ch requeue-sentinel-dead-ch))
        :apply-fn-loop (thread (apply-fn-loop apply-fn-ch compress-batch-ch apply-fn-dead-ch))
        :compress-batch-loop (thread (compress-batch-loop compress-batch-ch write-batch-ch compress-batch-dead-ch))
        :write-batch-loop (thread (write-batch-loop write-batch-ch status-check-ch write-batch-dead-ch))
        :status-check-loop (thread (status-check-loop status-check-ch ack-ch status-check-dead-ch))
        :ack-loop (thread (ack-loop ack-ch commit-tx-ch ack-dead-ch))
        :commit-tx-loop (thread (commit-tx-loop commit-tx-ch close-resources-ch commit-tx-dead-ch))
        :close-resources-loop (thread (close-resources-loop close-resources-ch close-temporal-ch close-resources-dead-ch))
        :close-temporal-loop (thread (close-temporal-loop close-temporal-ch reset-payload-node-ch close-temporal-dead-ch))
        :reset-payload-node-loop (thread (reset-payload-node-loop reset-payload-node-ch seal-ch reset-payload-node-dead-ch))
        :seal-resource-loop (thread (seal-resource-loop seal-ch complete-task-ch seal-dead-ch))
        :complete-task-loop (thread (complete-task-loop complete-task-ch complete-task-dead-ch))

        :pipeline-data pipeline-data)))

  (stop [component]
    (taoensso.timbre/info "Stopping Task Pipeline")

    (close! (:open-session-kill-ch component))
    (<!! (:open-session-dead-ch component))

    (close! (:read-batch-ch component))
    (<!! (:read-batch-dead-ch component))

    (close! (:decompress-batch-ch component))
    (<!! (:decompress-batch-dead-ch component))

    (close! (:strip-sentinel-ch component))
    (<!! (:strip-sentinel-dead-ch component))

    (close! (:requeue-sentinel-ch component))
    (<!! (:requeue-sentinel-dead-ch component))

    (close! (:apply-fn-ch component))
    (<!! (:apply-fn-dead-ch component))

    (close! (:compress-batch-ch component))
    (<!! (:compress-batch-dead-ch component))

    (close! (:write-batch-ch component))
    (<!! (:write-batch-dead-ch component))

    (close! (:status-check-ch component))
    (<!! (:status-check-dead-ch component))

    (close! (:ack-ch component))
    (<!! (:ack-dead-ch component))

    (close! (:commit-tx-ch component))
    (<!! (:commit-tx-dead-ch component))

    (close! (:close-resources-ch component))
    (<!! (:close-resources-dead-ch component))
    
    (close! (:close-temporal-ch component))
    (<!! (:close-temporal-dead-ch component))

    (close! (:reset-payload-node-ch component))
    (<!! (:reset-payload-node-dead-ch component))

    (close! (:seal-ch component))
    (<!! (:seal-dead-ch component))

    (close! (:complete-task-ch component))
    (<!! (:complete-task-dead-ch component))

    (close! (:open-session-dead-ch component))
    (close! (:read-batch-dead-ch component))
    (close! (:decompress-batch-dead-ch component))
    (close! (:strip-sentinel-dead-ch component))
    (close! (:requeue-sentinel-dead-ch component))
    (close! (:apply-fn-dead-ch component))
    (close! (:compress-batch-dead-ch component))
    (close! (:write-batch-dead-ch component))
    (close! (:status-check-dead-ch component))
    (close! (:ack-dead-ch component))
    (close! (:commit-tx-dead-ch component))
    (close! (:close-resources-dead-ch component))
    (close! (:close-temporal-dead-ch component))
    (close! (:reset-payload-node-dead-ch component))
    (close! (:seal-dead-ch component))
    (close! (:complete-task-dead-ch component))

    (internal-ext/close-pipeline-resources (:pipeline-data component))
    (p-ext/close-pipeline-resources (:pipeline-data component))

    component))

(defn task-pipeline [id payload sync queue payload-ch complete-ch fn-params]
  (map->TaskPipeline {:id id :payload payload :sync sync
                      :queue queue :payload-ch payload-ch
                      :complete-ch complete-ch :fn-params fn-params}))

(dire/with-post-hook! #'munge-open-session
  (fn [{:keys [id]}]
    (taoensso.timbre/info (format "[%s] Created new tx session" id))))

(dire/with-post-hook! #'munge-read-batch
  (fn [{:keys [id batch]}]
    (taoensso.timbre/info (format "[%s] Read %s segments" id (count batch)))))

(dire/with-post-hook! #'munge-strip-sentinel
  (fn [{:keys [id decompressed]}]
    (taoensso.timbre/info (format "[%s] Stripped sentinel. %s segments left" id (count decompressed)))))

(dire/with-post-hook! #'munge-requeue-sentinel
  (fn [{:keys [id tail-batch?]}]
    (taoensso.timbre/info (format "[%s] Requeued sentinel value" id))))

(dire/with-post-hook! #'munge-decompress-batch
  (fn [{:keys [id decompressed batch]}]
    (taoensso.timbre/info (format "[%s] Decompressed %s segments" id (count decompressed)))))

(dire/with-post-hook! #'munge-apply-fn
  (fn [{:keys [id results]}]
    (taoensso.timbre/info (format "[%s] Applied fn to %s segments" id (count results)))))

(dire/with-post-hook! #'munge-compress-batch
  (fn [{:keys [id compressed]}]
    (taoensso.timbre/info (format "[%s] Compressed %s segments" id (count compressed)))))

(dire/with-post-hook! #'munge-write-batch
  (fn [{:keys [id]}]
    (taoensso.timbre/info (format "[%s] Wrote batch" id))))

(dire/with-post-hook! #'munge-status-check
  (fn [{:keys [id status-node]}]
    (taoensso.timbre/info (format "[%s] Checked the status node" id))))

(dire/with-post-hook! #'munge-ack
  (fn [{:keys [id acked]}]
    (taoensso.timbre/info (format "[%s] Acked %s segments" id acked))))

(dire/with-post-hook! #'munge-commit-tx
  (fn [{:keys [id commit?]}]
    (taoensso.timbre/info (format "[%s] Committed transaction? %s" id commit?))))

(dire/with-post-hook! #'munge-close-resources
  (fn [{:keys [id]}]
    (taoensso.timbre/info (format "[%s] Closed resources" id))))

(dire/with-post-hook! #'munge-close-temporal-resources
  (fn [{:keys [id]}]
    (taoensso.timbre/info (format "[%s] Closed temporal plugin resources" id))))

(dire/with-post-hook! #'munge-seal-resource
  (fn [{:keys [id]}]
    (taoensso.timbre/info (format "[%s] Sealing resource" id))))

