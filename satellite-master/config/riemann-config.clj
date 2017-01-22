;; Copyright 2015 TWO SIGMA OPEN SOURCE, LLC
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(require '[clj-http.client :as client])

(logging/init {:file "log/riemann.log"})

(tcp-server)
(ws-server)

;; default tracker url and params
(def tracker-url "http://xxx.com/someMethod?creatorKey=xxx")
(def external-monitor-id-map {"totak-task-id" "1000001", "master-restarted-id" "1000002", "low-slave-active-id" "1000003", "high-cpus-percent-id" "1000004", "high-mem-percent-id" "1000005", "tasks-lost-id" "1000006",})
(def base-params {})

;; default emailer uses localhost
(def email (mailer))
(def indx (index))

;; expire expired events every 5 seconds
(periodically-expire 5)

(def task-totals-time-stream
  (by :host
      (combine difference-since-beginning
               (where (> (:interval event) 600)
                      (project [(service "mesos/slave/total-tasks-failed")
                                (service "mesos/slave/total-tasks-started")
                                (service "mesos/slave/total-tasks-finished")]
                               (smap (partial fold-blackhole-thresholds satellite.core/settings)
                                     (where (state "critical")
                                            #(warn "Host failing too many tasks:" %)
                                            (throttle 1 900
                                                      #(warn "Emailing administration about task black hole." %)
                                                      (client/post tracker-url
                                                      {:form-params (conj base-params 
                                                                     {"title" "The mesos may occur task black hole", 
                                                                     "detail" "Check service of mesos/slave/total-tasks% for more info",
                                                                     "external_monitor_id" (get external-monitor-id-map "totak-task-id"),
                                                                     "occur_time" (System/currentTimeMillis)})})))))))))

;; this stream need test
;;(def tracker-stream
;;  (client/get tracker-url
;;        {:query-params (fn [title detail] (conj base-params {"title" title, "detail" detail}))}))

(streams
 indx
 (where (service #"satellite.*")
        prn)
  (where (service #"mesos.*")
        #(info %))
 (where (service #"mesos/slave.*")
        prn
        ;; if a host/service pair changes state, update global state
        (changed-state
         (where (state "ok")
                delete-event
                (else
                 persist-event)))
        ;; If we stop receiving any test from a host, remove that host
        ;; from the whitelist. We don't want to send tasks to a host
        ;; that is (a) experiencing a network partition or (b) whose
        ;; tests are timing-out. If it is (c) that the satellite-slave
        ;; process is down, this at least warrants investigation.
        (where* expired?
                (fn [event]
                  (warn "Removing host due to expired event" (:host event))
                  (off-host (:host event)))
                ;; Otherwise make sure all tests pass on each host
                (else
                 (coalesce 60
                           ensure-all-tests-pass))))

 ;; check task run totals to detect task host black holes
 (where (service #"mesos/slave/total-tasks-.*")
        (by [:host :service]
            (moving-time-window (:blackhole-check-seconds satellite.core/settings)
                                task-totals-time-stream)))

;; mesos master is restarted
(where (and (service #"mesos/master/uptime_secs")
             (< metric 60))
        #(warn "mesos master has been restarted recently:" %)
        (throttle 1 3600
                (fn [event] (client/post tracker-url 
                                 {:form-params (conj base-params 
                                               {"title" "Mesos master has been restarted recently", 
                                               "detail" (apply str "In recent 60s, mesos master has been restarted, the host is " (get event :host)),
                                               "external_monitor_id" (get external-monitor-id-map "master-restarted-id"),
                                               "occur_time" (System/currentTimeMillis)})}))))

(where (and (service #"mesos/master/slaves_active")
             (< metric 3))
        #(warn "mesos slaves_active is low:" %)
        (throttle 3 3600
                (client/post tracker-url
                    {:form-params (conj base-params 
                                               {"title" "The master/slaves_active is low", 
                                               "detail" "slaves_active is less than 3",
                                               "external_monitor_id" (get external-monitor-id-map "low-slave-active-id"),
                                               "occur_time" (System/currentTimeMillis)})})))

;; master/cpus_percent > 0.9 for sustained periods of time
(where (and (service #"mesos/master/cpus_percent")
             (> metric 0.9))
        #(warn "master/cpus_percent > 0.9 for sustained periods of time:" %)
        (throttle 3 3600
               (client/post tracker-url
                    {:form-params (conj base-params 
                                               {"title" "The master/cpus_percent > 0.9 for sustained periods of time", 
                                               "detail" "Cluster CPU utilization is close to capacity",
                                               "external_monitor_id" (get external-monitor-id-map "high-cpus-percent-id"),
                                               "occur_time" (System/currentTimeMillis)})})))

;; master/mem_percent > 0.9 for sustained periods of time
(where (and (service #"mesos/master/mem_percent")
             (> metric 0.9))
        #(warn "master/mem_percent > 0.9 for sustained periods of time:" %)
        (throttle 3 3600
            (client/post tracker-url
                    {:form-params (conj base-params 
                                               {"title" "The master/mem_percent > 0.9 for sustained periods of time", 
                                               "detail" "Cluster memory utilization is close to capacity",
                                               "external_monitor_id" (get external-monitor-id-map "high-mem-percent-id"),
                                               "occur_time" (System/currentTimeMillis)})})))

;; master/tasks_lost is increasing
(where (service #"mesos/master/tasks_lost")
        #(warn "master/tasks_lost is increasing:" %)
        (changed :metric
            (throttle 3 3600
                (fn [event] (client/post tracker-url 
                                 {:form-params (conj base-params 
                                               {"title" "The master/tasks_lost is increasing", 
                                               "detail" (apply str "The master/tasks_lost is increasing, the metric is " (get event :metric)),
                                               "external_monitor_id" (get external-monitor-id-map "tasks-lost-id"),
                                               "occur_time" (System/currentTimeMillis)})}))))))

