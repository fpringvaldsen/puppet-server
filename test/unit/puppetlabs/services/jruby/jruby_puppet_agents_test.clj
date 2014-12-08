(ns puppetlabs.services.jruby.jruby-puppet-agents-test
  (:require [clojure.test :refer :all]
            [schema.test :as schema-test]
            [puppetlabs.trapperkeeper.testutils.bootstrap :as tk-testutils]
            [puppetlabs.services.jruby.jruby-puppet-service :as jruby]
            [puppetlabs.services.puppet-profiler.puppet-profiler-service :as profiler]
            [puppetlabs.services.jruby.jruby-testutils :as jruby-testutils]
            [puppetlabs.services.jruby.jruby-puppet-core :as jruby-core]
            [puppetlabs.trapperkeeper.app :as tk-app]
            [puppetlabs.trapperkeeper.services :as tk-services]
            [puppetlabs.services.protocols.jruby-puppet :as jruby-protocol])
  (:import (puppetlabs.services.jruby.jruby_puppet_core RetryPoisonPill)
           (com.puppetlabs.puppetserver JRubyPuppet)
           (java.util.concurrent ArrayBlockingQueue)))

(use-fixtures :once schema-test/validate-schemas)
(use-fixtures :each jruby-testutils/mock-pool-instance-fixture)

(defn reduce-over-jrubies!
  "Utility function; takes a JRuby pool and size, and a function f from integer
  to string.  For each JRuby instance in the pool, f will be called, passing in
  an integer offset into the jruby array (0..size), and f is expected to return
  a string containing a script to run against the jruby instance.

  Returns a vector containing the results of executing the scripts against the
  JRuby instances."
  [pool size f]
  (let [jrubies (jruby-testutils/drain-pool pool size)
        result  (reduce
                  (fn [acc jruby-offset]
                    (let [sc (:scripting-container (nth jrubies jruby-offset))
                          script (f jruby-offset)
                          result (.runScriptlet sc script)]
                      (conj acc result)))
                  []
                  (range size))]
    (jruby-testutils/fill-drained-pool jrubies)
    result))

(deftest basic-flush-test
  (testing "Flushing the pool results in all new JRuby instances"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config 4)))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            pool (jruby-core/get-pool pool-context)]
        (reduce-over-jrubies! pool 4 #(format "InstanceID = %s" %))
        (is (= #{0 1 2 3}
               (-> (reduce-over-jrubies! pool 4 (constantly "InstanceID"))
                   set)))
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until the flush is complete
        (await (:pool-agent context))
        (let [new-pool (jruby-core/get-pool pool-context)]
         (is (every? true?
                     (reduce-over-jrubies!
                       new-pool
                       4
                       (constantly
                         "begin; InstanceID; false; rescue NameError; true; end")))))))))

(deftest retry-poison-pill-test
  (testing "Flush puts a retry poison pill into the old pool"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config 1)))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            old-pool (jruby-core/get-pool pool-context)
            pool-state-swapped (promise)
            pool-state-watch-fn (fn [key pool-state old-val new-val]
                                  (when (not= (:pool old-val) (:pool new-val))
                                    (remove-watch pool-state key)
                                    (deliver pool-state-swapped true)))]
        ; borrow an instance so we know that the pool is ready
        (jruby/with-jruby-puppet jruby-puppet jruby-service)
        (add-watch (:pool-state pool-context) :pool-state-watch pool-state-watch-fn)
        (jruby-protocol/flush-jruby-pool! jruby-service)
        ; wait until we know the new pool has been swapped in
        @pool-state-swapped
        ; wait until the flush is complete
        (await (:pool-agent context))
        (let [old-pool-instance (jruby-core/borrow-from-pool old-pool)]
          (is (jruby-core/retry-poison-pill? old-pool-instance)))))))

(deftest with-jruby-retry-test-via-fake-pool
  (testing "with-jruby-puppet retries if it encounters a RetryPoisonPill"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config 2)))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            context (tk-services/service-context jruby-service)
            pool-context (:pool-context context)
            pool (jruby-core/get-pool pool-context)]
        ; borrow and return an instance so we know that the pool is ready
        (jruby/with-jruby-puppet jruby-puppet jruby-service)
        ; empty the pool so we can do some evil things to it
        (let [instances (jruby-testutils/drain-pool pool 2)
              instance  (first instances)]
          ; put a retry pill into the pool
          (jruby-core/return-to-pool (RetryPoisonPill. pool))
          ; put one of the instances back
          (jruby-core/return-to-pool instance)
          (jruby/with-jruby-puppet jruby-puppet jruby-service
            (is (instance? JRubyPuppet jruby-puppet))))))))

(deftest with-jruby-retry-test-via-mock-get-pool
  (testing "with-jruby-puppet retries if it encounters a RetryPoisonPill"
    (tk-testutils/with-app-with-config
      app
      [jruby/jruby-puppet-pooled-service
       profiler/puppet-profiler-service]
      (-> (jruby-testutils/jruby-puppet-tk-config
            (jruby-testutils/jruby-puppet-config 1)))
      (let [jruby-service (tk-app/get-service app :JRubyPuppetService)
            real-pool     (-> (tk-services/service-context jruby-service)
                              :pool-context
                              (jruby-core/get-pool))
            retry-pool    (ArrayBlockingQueue. 1)
            _             (-> retry-pool (RetryPoisonPill.) jruby-core/return-to-pool)
            mock-pools    [retry-pool retry-pool retry-pool real-pool]
            num-borrows   (atom 0)
            get-mock-pool (fn [_] (let [result (nth mock-pools @num-borrows)]
                                    (swap! num-borrows inc)
                                    result))]
        (with-redefs [jruby-core/get-pool get-mock-pool]
          (jruby/with-jruby-puppet
            jruby-puppet
            jruby-service
            (is (instance? JRubyPuppet jruby-puppet))))
        (is (= 4 @num-borrows))))))