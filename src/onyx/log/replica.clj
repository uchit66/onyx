(ns onyx.log.replica)

(def base-replica 
  {:peers []
   :peer-state {}
   :peer-sites {}
   :prepared {}
   :accepted {}
   :pairs {}
   :jobs []
   :task-schedulers {}
   :tasks {}
   :task-name->id {}
   :allocations {}
   :task-saturation {}
   :saturation {}
   :flux-policies {}
   :min-required-peers {}
   :input-tasks {}
   :output-tasks {}
   :exempt-tasks {}
   :exhausted-inputs {}
   :ackers {}
   :acker-percentage {}
   :acker-exclude-inputs {}
   :acker-exclude-outputs {}
   :task-percentages {}
   :task-metadata {}
   :percentages {}
   :completed-jobs []
   :killed-jobs []
   :state-logs {} 
   :state-logs-marked #{}
   :task-slot-ids {}
   :required-tags {}
   :peer-tags {}})
