(ns me.untethr.nostr.app
  (:require
    [clojure.java.io :as io]
    [clojure.tools.logging :as log]
    [me.untethr.nostr.common :as common]
    [me.untethr.nostr.conf :as conf]
    [me.untethr.nostr.crypt :as crypt]
    [me.untethr.nostr.extra :as extra]
    [me.untethr.nostr.fulfill :as fulfill]
    [me.untethr.nostr.page.home :as page-home]
    [me.untethr.nostr.page.metrics-porcelain :as metrics-porcelain]
    [me.untethr.nostr.page.nip11 :as page-nip11]
    [me.untethr.nostr.jetty :as jetty]
    [me.untethr.nostr.common.json-facade :as json-facade]
    [me.untethr.nostr.common.metrics :as metrics]
    [me.untethr.nostr.store :as store]
    [me.untethr.nostr.subscribe :as subscribe]
    [me.untethr.nostr.util :as util]
    [me.untethr.nostr.validation :as validation]
    [me.untethr.nostr.write-thread :as write-thread]
    [me.untethr.nostr.ws-registry :as ws-registry]
    [next.jdbc :as jdbc])
  (:import (com.codahale.metrics MetricRegistry)
           (jakarta.servlet.http HttpServletRequest)
           (java.nio.channels ClosedChannelException WritePendingException)
           (java.nio.charset StandardCharsets)
           (java.util UUID)
           (java.io File)
           (java.util.concurrent.atomic AtomicInteger)
           (javax.sql DataSource)
           (me.untethr.nostr.conf Conf)
           (org.eclipse.jetty.io EofException)
           (org.eclipse.jetty.server Server)
           (org.eclipse.jetty.server.handler StatisticsHandler)
           (org.eclipse.jetty.websocket.api BatchMode ExtensionConfig Session)
           (org.eclipse.jetty.websocket.common WebSocketSession)
           (org.eclipse.jetty.websocket.server JettyServerUpgradeRequest JettyServerUpgradeResponse JettyWebSocketCreator)))

(def parse json-facade/parse)
(def write-str* json-facade/write-str*)
(def current-system-epoch-seconds util/current-system-epoch-seconds)

(defn- calc-event-id
  [{:keys [pubkey created_at kind tags content]}]
  (-> [0 pubkey created_at kind tags content]
    write-str*
    (.getBytes StandardCharsets/UTF_8)
    crypt/sha-256
    crypt/hex-encode))

(defn verify
  [public-key message signature]
  (crypt/verify
    (crypt/hex-decode public-key)
    (crypt/hex-decode message)
    (crypt/hex-decode signature)))

(defn- as-verified-event
  "Verify the event. Does it have all required properties with values of
   expected types? Is its event id calculated correctly? Is its signature
   verified? If everything looks good we will return the provided event
   itself. Otherwise, we return a map with an :err key indicating the error."
  [{:keys [id sig pubkey] :as e}]
  (if-let [err-key (validation/event-err e)]
    {:event e :err "invalid event" :context (name err-key)}
    (let [calculated-id (calc-event-id e)]
      (if-not (= id calculated-id)
        {:event e :err "bad event id"}
        (if-not (verify pubkey id sig)
          {:event e :err "event did not verify"}
          e)))))


(defn- create-event-message
  ^String [req-id raw-event]
  ;; important: req-id may be null
  ;; careful here! we're stitching the json ourselves b/c we have the raw event:
  (format "[\"EVENT\",%s,%s]" (write-str* req-id) raw-event))

(defn- create-eose-message
  ^String [req-id]
  ;; important: req-id may be null
  ;; @see https://github.com/nostr-protocol/nips/blob/master/15.md
  (format "[\"EOSE\",%s]" (write-str* req-id)))

(defn- create-notice-message
  ^String [message]
  (write-str* ["NOTICE" message]))

(defn- create-ok-message
  ^String [event-id bool-val message]
  ;; @see https://github.com/nostr-protocol/nips/blob/master/20.md
  (write-str* ["OK" event-id bool-val message]))

(defn- update-outgoing-messages!
  [websocket-state op-keyword]
  (let [rv (case op-keyword
             :inc (.incrementAndGet (:outgoing-messages websocket-state))
             :dec (.decrementAndGet (:outgoing-messages websocket-state)))]
    (log/debugf "%s outgoing messages: %d" (:uuid websocket-state) rv)
    rv))

(defn- send!*
  ([websocket-state ch-sess data]
   (send!* websocket-state ch-sess data false))
  ([websocket-state ch-sess data flush?]
   (send!* websocket-state ch-sess data flush? nil))
  ([websocket-state ^Session ch-sess ^String data flush? context]
   (if-not (.isOpen ch-sess)
     (log/debug "refusing to send; channel was closed" {:context context})
     (do
       (update-outgoing-messages! websocket-state :inc)
       (try
         (jetty/send! ch-sess data
           (fn []
             (update-outgoing-messages! websocket-state :dec))
           (fn [^Throwable t]
             (update-outgoing-messages! websocket-state :dec)
             (cond
               (instance? ClosedChannelException t)
               (log/debug "failed to send; channel was closed" {:context context})
               (instance? WritePendingException t)
               (log/warn
                 (str "exceeded max outgoing websocket frames"
                   " dropping messages but not closing channel")
                 (:uuid websocket-state))
               (instance? EofException t)
               (log/debug "failed to send; channel closed abruptly?" {:context context})
               :else
               (log/warn "failed to send"
                 {:exc-type (type t)
                  :exc-message (.getMessage t)
                  :context context})))
           flush?)
         (catch Throwable t
           ;; completely unexpected but here to protect outgoing-messages tally
           ;; we don't expect WriteCallback from jetty/send! to throw either,
           ;; but if they do, should be on separate jetty processing thread, so
           ;; tally should not double-decrement in completely worst cases.
           (log/error t "unexpected exception from jetty/send!")
           (update-outgoing-messages! websocket-state :dec)))))))

(defn- handle-duplicate-event!
  [metrics websocket-state ch-sess event ok-message-str]
  (metrics/duplicate-event! metrics)
  (send!* websocket-state ch-sess
    (create-ok-message
      (:id event)
      true
      (format "duplicate:%s" ok-message-str))
    true ;; flush?
    'handle-duplicate-event!))

(defn- handle-stored-or-replaced-event!
  [_metrics websocket-state ch-sess event ok-message-str]
  (send!* websocket-state ch-sess
    (create-ok-message (:id event) true (format ":%s" ok-message-str))
    true ;; flush?
    'handle-stored-or-replaced-event!))

(defn- store-event!
  ([db event-obj raw-event]
   (store-event! db nil event-obj raw-event))
  ([db channel-id {:keys [id pubkey created_at kind tags] :as _e} raw-event]
   ;; use a tx, for now; don't want to answer queries with events
   ;; that don't fully exist. could denormalize or some other strat
   ;; to avoid tx if needed
   (jdbc/with-transaction [tx db]
     (if-let [rowid (store/insert-event! tx id pubkey created_at kind raw-event channel-id)]
       (do
         (doseq [[tag-kind arg0] tags]
           (cond
             (= tag-kind "e") (store/insert-e-tag! tx id arg0)
             (= tag-kind "p") (store/insert-p-tag! tx id arg0)
             (common/indexable-tag-str?* tag-kind) (store/insert-x-tag! tx id tag-kind arg0)
             :else :no-op))
         rowid)
       :duplicate))))

(defn- handle-invalid-event!
  [metrics websocket-state ch-sess event err-map]
  (log/debug "dropping invalid/unverified event" {:e event})
  (metrics/invalid-event! metrics)
  (let [event-id (:id event)]
    (if (validation/is-valid-id-form? event-id)
      (send!* websocket-state ch-sess
        (create-ok-message
          event-id
          false ;; failed!
          (format "invalid:%s%s" (:err err-map)
            (if (:context err-map)
              (str " (" (:context err-map) ")") "")))
        true ;; flush?
        'ok-false-invalid-event)
      (send!* websocket-state ch-sess
        (create-notice-message (str "Badly formed event id: " event-id))
        true ;; flush?
        'notice-invalid-event))))

(defn- reject-event-before-verify?
  "Before even verifying the event, can we determine that we'll reject it?
   This function returns nil for no rejection, otherwise it returns a short
   human-readable reason for the rejection."
  [^Conf conf event-obj]
  (cond
    ;; only validate created_at if it's available as a number; the actual
    ;; event verification step will fail for non-numeric or missing created_at.
    (and
      (:optional-max-created-at-delta conf)
      (number? (:created_at event-obj))
      (> (:created_at event-obj)
        (+ (util/current-system-epoch-seconds)
          (:optional-max-created-at-delta conf))))
    (format "event \"created_at\" too far in the future" (:created_at event-obj))
    ;; only validate kind if it's available as a number; the actual
    ;; event verification step will fail for non-numeric or missing kinds.
    (and
      (number? (:kind event-obj))
      (not (conf/supports-kind? conf (:kind event-obj))))
    (format "event \"kind\" '%s' not supported" (:kind event-obj))
    ;; only validate content if it's available as a string; the actual
    ;; event verification step will fail for bad content types.
    (and
      (:optional-max-content-length conf)
      (string? (get event-obj :content))
      (> (alength ^bytes (.getBytes ^String (get event-obj :content)))
        (:optional-max-content-length conf)))
    (format "event \"content\" too long; maximum content length is %d"
      (:optional-max-content-length conf))))

(defn- handle-rejected-event!
  [metrics websocket-state ch-sess event-obj rejection-message-str]
  (metrics/rejected-event! metrics)
  (send!* websocket-state ch-sess
    (create-ok-message
      (:id event-obj)
      false ;; failed
      (format "rejected:%s" rejection-message-str))
    true ;; flush?
    'ok-false-rejected-event))

(defn- replaceable-event?
  [event-obj]
  ;; https://github.com/nostr-protocol/nips/blob/master/16.md
  (some-> event-obj :kind (#(<= 10000 % (dec 20000)))))

(defn- ephemeral-event?
  [event-obj]
  ;; https://github.com/nostr-protocol/nips/blob/master/16.md
  (some-> event-obj :kind (#(<= 20000 % (dec 30000)))))

(defn- receive-accepted-event!
  [^Conf conf metrics db subs-atom channel-id websocket-state ch-sess event-obj _raw-message]
  (let [verified-event-or-err-map (metrics/time-verify! metrics (as-verified-event event-obj))]
    (if (identical? verified-event-or-err-map event-obj)
      ;; For now, we re-render the raw event into json; we could be faster by
      ;; stealing the json from the raw message via state machine or regex
      ;; instead of serializing it again here, but we'll also order keys (as an
      ;; unnecessary nicety) so that clients see a predictable payload form.
      ;; This raw-event is what we'll send to subscribers and we'll also write
      ;; it in the db when fulfilling queries of future subscribers.
      (let [raw-event (json-facade/write-str-order-keys* verified-event-or-err-map)]
        (cond
          (ephemeral-event? event-obj)
          (do
            ;; per https://github.com/nostr-protocol/nips/blob/master/20.md: "Ephemeral
            ;; events are not acknowledged with OK responses, unless there is a failure."
            (metrics/time-notify-event! metrics
              (subscribe/notify! metrics subs-atom event-obj raw-event)))
          :else
          ;; note: we are not at this point handling nip-16 replaceable events *in code*;
          ;; see https://github.com/nostr-protocol/nips/blob/master/16.md
          ;; currently a sqlite trigger is handling this for us, so we can go through the
          ;; standard storage handling here. incidentally this means we'll handle
          ;; duplicates the same for non-replaceable events (it should be noted that
          ;; this means we'll send a "duplicate:" nip-20 response whenever someone sends a
          ;; replaceable event that has already been replaced, and we'll bear that cross)
          (write-thread/run-async!
            (fn []
              (metrics/time-store-event! metrics
                (store-event! db channel-id verified-event-or-err-map raw-event)))
            (fn [store-result]
              (if (identical? store-result :duplicate)
                (handle-duplicate-event! metrics websocket-state ch-sess event-obj "duplicate")
                (do
                  (handle-stored-or-replaced-event! metrics websocket-state ch-sess event-obj "stored")
                  ;; Notify subscribers only after we discover that the event is
                  ;; not a duplicate.
                  (metrics/time-notify-event! metrics
                    (subscribe/notify! metrics subs-atom event-obj raw-event)))))
            (fn [^Throwable t]
              (log/error t "while storing event" event-obj)))))
      ;; event was invalid, per nip-20, we'll send make an indication that the
      ;; event did not get persisted (see
      ;; https://github.com/nostr-protocol/nips/blob/master/20.md)
      (handle-invalid-event! metrics websocket-state ch-sess event-obj verified-event-or-err-map))))

(defn- receive-event
  [^Conf conf metrics db subs-atom channel-id websocket-state ch-sess [_ e] raw-message]
  ;; Before we attempt to validate an event and its signature (which costs us
  ;; some compute), we'll determine if we're destined to reject the event anyway.
  ;; These are generally rules from configuration - for example, if the event
  ;; content is too long, its timestamp too far in the future, etc, then we can
  ;; reject it without trying to validate it.
  (if-let [rejection-reason (reject-event-before-verify? conf e)]
    (handle-rejected-event! metrics websocket-state ch-sess e rejection-reason)
    (receive-accepted-event! ^Conf conf metrics db subs-atom channel-id websocket-state ch-sess e raw-message)))

(def max-filters 20)

;; some clients may still send legacy filter format that permits singular id
;; in filter; so we'll support this for a while.
(defn- ^:deprecated interpret-legacy-filter
  [f]
  (cond-> f
    (and
      (contains? f :id)
      (not (contains? f :ids)))
    (-> (assoc :ids [(:id f)]) (dissoc :id))))

(defn- prepare-req-filters
  "Prepare subscription \"REQ\" filters before processing them. This includes
   removing filters that could never match anything, removing filters referencing
   kinds none of which we support, removing duplicate filters..."
  [^Conf conf req-filters]
  ;; consider; another possible optimization would be to remove filters that
  ;; are a subset of other filtring.middleware.params/wrap-paramsers in the group.
  (->> req-filters
    ;; conform...
    (map (comp validation/conform-filter-lenient
           interpret-legacy-filter))
    ;; remove null filters (ie filters that could never match anything)...
    (filter (complement validation/filter-has-empty-attr?))
    ;; if :kind is present, at least one of the provided kinds is supported
    (filter #(or (not (contains? % :kinds))
               (some (partial conf/supports-kind? conf) (:kinds %))))
    ;; remove duplicates...
    distinct
    vec))

(defn- fulfill-synchronously?
  [req-filters]
  (and (= (count req-filters) 1)
    (some-> req-filters (nth 0) :limit (= 1))))

(defn- ->internal-req-id
  [req-id]
  (or req-id "<null>"))

(defn- receive-req
  "This function defines how we handle any requests that come on an open websocket
   channel. We expect these to be valid nip-01 defined requests, but we don't
   assume all requests are valid and handle invalid requests in relevant ways."
  [^Conf conf metrics db subs-atom fulfill-atom channel-id websocket-state ch-sess [_ req-id & req-filters]]
  (if-not (every? map? req-filters)
    ;; Some filter in the request was not an object, so nothing we can do but
    ;; send back a NOTICE:
    (do
      (log/warn "invalid req" {:msg "expected filter objects"})
      (send!* websocket-state ch-sess
        (create-notice-message "\"REQ\" message had bad/non-object filter")
        true ;; flush?
        'notice-invalid-req))
    ;; else -- our req has at least the basic expected form.
    (let [use-req-filters (prepare-req-filters conf req-filters)
          ;; we've seen null subscription ids from clients in the wild, so we'll
          ;; begrudgingly support them by coercing to string here -- note that
          ;; we call it "internal" because any time we send the id back to the
          ;; client we'll want to use the original (possibly nil) id.
          internal-req-id (->internal-req-id req-id)
          req-err (validation/req-err internal-req-id use-req-filters)]
      (if req-err
        (log/warn "invalid req" {:req-err req-err :req [internal-req-id use-req-filters]})
        ;; else --
        (do
          ;; just in case we're still fulfilling prior subscription with the
          ;; same req-id, we need to cancel that fulfillment and remove any
          ;; subscriptions for the incoming req subscription id.
          (fulfill/cancel! fulfill-atom channel-id internal-req-id)
          (subscribe/unsubscribe! subs-atom channel-id internal-req-id)
          (if (empty? use-req-filters)
            (do
              ;; an empty set of filters at this point isn't going to produce
              ;; any results, so no need to do anything for it except send
              ;; a gratutious eose message to the sender to say they got
              ;; everything.
              (send!* websocket-state ch-sess (create-eose-message req-id)
                true ;; flush?
                'eose-short-circuit))
            (if (> (subscribe/num-filters subs-atom channel-id) max-filters)
              (do
                ;; The channel already has too many subscriptions, so we send
                ;; a NOTICE back to that effect and do nothing else.
                (metrics/inc-excessive-filters! metrics)
                (send!* websocket-state ch-sess
                  (create-notice-message
                    (format
                      (str
                        "Too many subscription filters."
                        " Max allowed is %d, but you have %d.")
                      max-filters
                      (subscribe/num-filters subs-atom channel-id)))
                  true ;; flush?
                  'notice-excessive-filters))
              (do
                ;; We create the incoming subscription first, so we are guaranteed
                ;; to dispatch new event arrivals from this point forward...
                (metrics/time-subscribe! metrics
                  (subscribe/subscribe! subs-atom channel-id internal-req-id use-req-filters
                    (fn subscription-observer [raw-event]
                      (send!* websocket-state ch-sess
                        ;; note: it's essential we use original possibly nil req-id here,
                        ;; note the internal one (see note above):
                        (create-event-message req-id raw-event)
                        true ;; flush?
                        'notify-subscription))))
                ;; After we create the subscription, we capture the current latest
                ;; rowid in the database. We will fullfill all matching messages up
                ;; to and including this row; in rare cases between the subscription
                ;; just above and the capturing of this rowid we may have recieved
                ;; some few number of messages in which case we may double-deliver
                ;; an event or few from both fulfillment and realtime notifications,
                ;; but, crucially, we will never miss an event:
                (if-let [target-row-id (store/max-event-rowid db)]
                  (letfn [;; this fullfillment observer function is a callback
                          ;; that will get invoked for every event in the db that
                          ;; matches the subscription.
                          (fulfillment-observer [raw-event]
                            (send!* websocket-state ch-sess (create-event-message req-id raw-event)
                              ;; this is the case where don't want to aggressively
                              ;; flush if websocket batching is enabled:
                              false
                              'fulfill-event))
                          ;; When the fulfillment completes successfully without
                          ;; getting cancelled, this callback will be invoked and
                          ;; we'll send an "eose" message (per nip-15)
                          (fulfillment-eose-callback []
                            (send!* websocket-state ch-sess (create-eose-message req-id)
                              true ;; flush?
                              'eose-standard))]
                    (if (fulfill-synchronously? use-req-filters)
                      ;; note: some requests -- like point lookups -- we'd like to *fulfill* asap
                      ;; w/in the channel request w/o giving up the current thread. assumption
                      ;; here is that we're responding to a client before we handle
                      ;; any other REQ or other event from the client -- and we're not going
                      ;; into fulfillment queues.
                      (fulfill/synchronous!!
                        metrics db channel-id internal-req-id use-req-filters target-row-id
                        fulfillment-observer fulfillment-eose-callback)
                      (fulfill/submit-use-batching!
                        metrics db fulfill-atom channel-id internal-req-id use-req-filters target-row-id
                        fulfillment-observer fulfillment-eose-callback)))
                  ;; should only occur on epochal first event
                  (log/warn "no max rowid; nothing yet to fulfill"))))))))))

(defn- receive-close
  [metrics _db subs-atom fulfill-atom channel-id _websocket-state _ch-sess [_ req-id]]
  (let [internal-req-id (->internal-req-id req-id)]
    (if-let [err (validation/close-err internal-req-id)]
      (log/warn "invalid close" {:err err :req-id internal-req-id})
      (do
        (metrics/time-unsubscribe! metrics
          (subscribe/unsubscribe! subs-atom channel-id internal-req-id))
        (fulfill/cancel! fulfill-atom channel-id internal-req-id)))))

(defn- parse-raw-message*
  [raw-message]
  (try
    (parse raw-message)
    (catch Exception e
      e)))

(defn- handle-problem-message!
  [metrics websocket-state ch-sess raw-message notice-message-str]
  (log/debug "dropping problem message" {:raw-message raw-message})
  (metrics/problem-message! metrics)
  (send!* websocket-state ch-sess
    (create-notice-message notice-message-str)
    true ;; flush?
    'notice-problem-message))

(defn- ws-receive
  [^Conf conf metrics db subs-atom fulfill-atom {:keys [uuid] :as websocket-state} ch-sess raw-message]
  ;; note: have verified that exceptions from here are caught, logged, and swallowed
  ;; by http-kit.
  ;; First thing we do is parse any message on the wire - we expect every message
  ;; to be in json format:
  (let [parsed-message-or-exc (parse-raw-message* raw-message)]
    (if (instance? Exception parsed-message-or-exc)
      ;; If we fail to parse a websocket message, we handle it - we send a NOTICE
      ;; message in response.
      (handle-problem-message! metrics websocket-state ch-sess raw-message (str "Parse failure on: " raw-message))
      (if (and (vector? parsed-message-or-exc) (not-empty parsed-message-or-exc))
        (condp = (nth parsed-message-or-exc 0)
          ;; These are the three types of message that nostr defines:
          "EVENT" (receive-event conf metrics db subs-atom uuid websocket-state ch-sess parsed-message-or-exc raw-message)
          "REQ" (receive-req conf metrics db subs-atom fulfill-atom uuid websocket-state ch-sess parsed-message-or-exc)
          "CLOSE" (receive-close metrics db subs-atom fulfill-atom uuid websocket-state ch-sess parsed-message-or-exc)
          ;; If we do not recongize the message type, then we also do not process
          ;; and send a NOTICE response.
          (handle-problem-message! metrics websocket-state ch-sess raw-message (str "Unknown message type: " (nth parsed-message-or-exc 0))))
        ;; If event parsed, but it was not a json array, we do not process it and
        ;; send a NOTICE response.
        (handle-problem-message! metrics websocket-state ch-sess raw-message (str "Expected a JSON array: " raw-message))))))

(defn- ws-open
  [metrics db websocket-connections-registry _subs-atom _fulfill-atom {:keys [uuid ip-address] :as websocket-state}]
  (ws-registry/add! websocket-connections-registry websocket-state)
  ;; We keep track of created channels and the ip address that created the channel
  ;; in our database. This allows us to do some forensics if we see any bad behavior
  ;; and need to blacklist any ips, for example. Note that all of our db writes
  ;; are done on a singleton write thread - this is optimal write behavior for
  ;; sqlite3 using WAL-mode:
  (write-thread/run-async!
    (fn [] (metrics/time-insert-channel! metrics
             (store/insert-channel! db uuid ip-address)))
    (fn [_] (log/debug "inserted channel" (:uuid websocket-state)))
    (fn [^Throwable t] (log/error t "while inserting new channel" (:uuid websocket-state))))
  ;; Without waiting fo the db write to occur, we can update our metrics and
  ;; return immedidately.
  (metrics/websocket-open! metrics))

(defn- ws-close
  [metrics _db websocket-connections-registry subs-atom fulfill-atom
   {:keys [uuid start-ns] :as websocket-state} _ch-sess _status]
  (ws-registry/remove! websocket-connections-registry websocket-state)
  ;; Update our metrics to close the websocket, recording also the duration of the
  ;; channel's lifespan.
  (metrics/websocket-close! metrics (util/nanos-to-millis (- (System/nanoTime) start-ns)))
  ;; We'll want to ensure that all subscriptions and associated state for the
  ;; websocket channel (uuid) are cleaned up and removed.
  (metrics/time-unsubscribe-all! metrics
    (subscribe/unsubscribe-all! subs-atom uuid))
  ;; And just in case we were fulfilling any subscriptions for the channel, we'll
  ;; cancel those as well.
  (fulfill/cancel-all! fulfill-atom uuid))

;; --

(defn- populate-non-ws-handler!
  ^StatisticsHandler [^StatisticsHandler non-ws-handler
                      ^StatisticsHandler ws-handler
                      ^Conf conf
                      nip05-json
                      nip11-json
                      metrics
                      db]
  (doto non-ws-handler
    (.setHandler
      (jetty/create-handler-list
        ;; order matters here -- want to consider nip11 handler first,
        ;; before deciding to serve home page and, later, the websocket
        ;; upgrade.
        ;; -- nip11 --
        (jetty/create-simple-handler
          (every-pred (jetty/uri-req-pred "/")
            (jetty/header-eq-req-pred "Accept" "application/nostr+json"))
          (fn [_req] {:status 200
                      :content-type "application/nostr+json"
                      :headers {"Access-Control-Allow-Origin" "*"}
                      :body (page-nip11/json nip11-json)}))
        ;; -- home page --
        (jetty/create-simple-handler
          (every-pred (jetty/uri-req-pred "/")
            (jetty/header-neq-req-pred "Connection" "upgrade"))
          (fn [_req] {:status 200
                      :content-type "text/html"
                      :body (page-home/html conf)}))
        ;; -- nip05 --
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/.well-known/nostr.json")
          (fn [_req] {:status 200
                      :content-type "application/json"
                      :body nip05-json}))
        ;; -- /q --
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/q")
          (fn [^HttpServletRequest req]
            {:status 200
             :content-type "text/plain"
             :body (extra/execute-q conf db prepare-req-filters
                     (jetty/->query-params req)
                     (jetty/->body-str req))}))
        ;; -- /metrics --
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/metrics")
          (fn [_req] {:status 200
                      :content-type "application/json"
                      ;; we can json-serialize the whole metrics registry here
                      ;; because we assume we have a jackson-metrics-module
                      ;; registered with the jackson object mapper.
                      :body (write-str* (:codahale-registry metrics))}))
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/metrics-porcelain")
          (fn [_req] {:status 200
                      :content-type "text/html"
                      ;; we can json-serialize the whole metrics registry here
                      ;; because we assume we have a jackson-metrics-module
                      ;; registered with the jackson object mapper.
                      :body (metrics-porcelain/html metrics)}))
        ;; todo - move these to /metrics and introduce /metrics-simple
        ;; -- /stats-jetty/not-ws --
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/stats-jetty/not-ws")
          (fn [_req] {:status 200
                      :content-type "text/html"
                      :body (.toStatsHTML non-ws-handler)}))
        ;; -- /stats-jetty/ws --
        (jetty/create-simple-handler
          (jetty/uri-req-pred "/stats-jetty/ws")
          (fn [_req] {:status 200
                      :content-type "text/html"
                      :body (.toStatsHTML ws-handler)}))))))

(defn- create-jetty-websocket-creator
  ^JettyWebSocketCreator [^Conf conf metrics db websocket-connections-registry
                          subs-atom fulfill-atom]
  (jetty/create-jetty-websocket-creator
    {:on-create
     (fn [^JettyServerUpgradeRequest req ^JettyServerUpgradeResponse resp]
       (let [created-state (ws-registry/->WebSocketConnectionState
                             (str (UUID/randomUUID))
                             (System/nanoTime)
                             (jetty/upgrade-req->ip-address req)
                             (AtomicInteger. 0))]
         (when (:websockets-disable-permessage-deflate? conf)
           ;; when deflate is disabled we save cpu at the network's expense
           ;; see https://github.com/eclipse/jetty.project/issues/1341
           (log/debug "disabling permessage-deflate" (:uuid created-state))
           (.setExtensions resp
             (filter #(not= "permessage-deflate"
                        (.getName ^ExtensionConfig %)) (.getExtensions req))))
         (log/debug 'ws-open (:uuid created-state) (:ip-address created-state))
         (ws-open metrics db websocket-connections-registry subs-atom fulfill-atom created-state)
         created-state))
     :on-connect
     (fn [created-state sess]
       (let [^WebSocketSession as-websocket-session (cast WebSocketSession sess)]
         ;; core session doesn't manage max outgoing frames in a thread-safe way
         ;; however, we assume we can set it here on connect and never again
         ;; so that subsequent threads observe updated value.
         ;; this is a safety measure to prevent a bad or slow client from
         ;; failing to consume events and causing us/jetty to accumuate
         ;; outgoing frames and consume memory.
         ;; per docs if this value is exceeded subsequent sends will fail
         ;; their WriteCallbacks with WritePendingException but the channel
         ;; won't otherwise close.
         ;; https://github.com/eclipse/jetty.project/issues/4824
         ;; practically, we expect this value to be higher than reasonable
         ;; for real-time notify events -- ie, clients should consume these
         ;; much faster than we'll produe them.
         ;; for fullfillment (historical), we'll need to take pains by using
         ;; WriteCallbacks so that we delay generating fulfillment events
         ;; until upstream client keeps up, or otherwise stop fulfilling for
         ;; client.
         (when (:websockets-max-outgoing-frames conf)
           (let [max-outgoing-frames (int (:websockets-max-outgoing-frames conf))]
             (log/debug "setting maxOutgoingFrames" max-outgoing-frames (:uuid created-state))
             (-> as-websocket-session
               .getCoreSession
               (.setMaxOutgoingFrames max-outgoing-frames))))
         (when (:websockets-enable-batch-mode? conf)
           (log/debug "enabling websockets batch mode" (:uuid created-state))
           (-> as-websocket-session
             .getRemote
             (.setBatchMode BatchMode/ON)))))
     :on-error
     (fn [created-state _sess ^Throwable t]
       ;; onError here should mean the websocket gets closed -- may see this
       ;; on abrupt client disconnects &c so log/debug and limit noise:
       (log/debug t "websocket on-error" created-state))
     :on-close
     ;; noting a jetty thing here: in case you see ClosedChannelExeptions logged at
     ;; debug in the logs, they are expected/no big deal.
     ;; see https://github.com/eclipse/jetty.project/issues/2699
     (fn [created-state sess status-code reason]
       (log/debug 'ws-close status-code reason (:uuid created-state))
       (ws-close metrics db websocket-connections-registry subs-atom fulfill-atom created-state sess status-code))
     :on-text-message
     (fn [created-state sess message]
       (ws-receive conf metrics db subs-atom fulfill-atom created-state sess message))}))

;; --

;; ?: policy for bad actors to avoid repeated bad parsing/verification/&c
;; ?: policy for firehose-filters; who can have them, max on server, ...
;; ?: rate-limit subscription requests, events, etc.

(defn start-server!
  "Start the server and block forever the main process thread."
  ^Server [^Conf conf nip05-json nip11-json]
  (let [websocket-connections-registry (ws-registry/create)
        ;; This is our subscription registry, stored in an atom for concurrent
        ;; access. All updates to this atom will be implementd in the subscribe
        ;; namespace.
        subs-atom (atom (subscribe/create-empty-subs))
        ;; This is our fulfillment registry, also in an atom. Whenever we
        ;; receive a nostr subscription (i.e., ["REQ", ...]) on a websocket
        ;; channel, we will register it in our subscription registry, but j
        ;; we also need to (what we call) "fulfill" the subscription by querying
        ;; and returning all historical data that matches the subscription.
        ;; Fulfillment may take time depending on how much historic data matches.
        ;; Fulfillment is handled in the fulfill namespace, and we keep state
        ;; in a registry so to support operations like cancelling fulfillments
        ;; (e.g., if a user sends a new subscription with the same id, we cancel
        ;; any corresponding ongoing fulfillment for that id; or if the websocket
        ;; is closed, we kill any associated fulfillments).
        fulfill-atom (atom (fulfill/create-empty-registry))
        ;; We have a cyclic dependency between our db/datasource and metric
        ;; registry. (Mainly we want the metric registry to be able to query
        ;; the db for max-rowid. But we also want our datasource to be able
        ;; to report metrics.) So we use a holder volatile so the metrics
        ;; registry can use it after it's been instantiated.
        db-holder (volatile! nil)
        non-ws-handler-container (StatisticsHandler.)
        ws-handler-container (StatisticsHandler.)
        ;; We report various server metrics and expose them via http for
        ;; observability (we use https://metrics.dropwizard.io/ for this):
        metrics (metrics/create-metrics
                  (util/memoize-with-expiration
                    #(if @db-holder
                       (store/max-event-rowid @db-holder) -1)
                    ;; just in case some queries /metrics endpoint in fast cycle
                    ;; we don't hit database each time.
                    5000)
                  #(ws-registry/size-estimate websocket-connections-registry)
                  #(subscribe/num-subscriptions subs-atom)
                  #(subscribe/num-filters-prefixes subs-atom)
                  #(subscribe/num-firehose-filters subs-atom)
                  #(fulfill/num-active-fulfillments fulfill-atom))
        ^DataSource db (store/init! (:sqlite-file conf) ^MetricRegistry (:codahale-registry metrics))
        _ (vreset! db-holder db)]
    (jetty/start-server!
      (populate-non-ws-handler!
        non-ws-handler-container
        ws-handler-container
        conf
        nip05-json
        nip11-json
        metrics
        db)
      ws-handler-container
      (create-jetty-websocket-creator
        conf
        metrics
        db
        websocket-connections-registry
        subs-atom
        fulfill-atom)
      {:port (:http-port conf)
       :host (:http-host conf)
       :max-ws 4194304})))

(defn- slurp-json*
  [f]
  (let [^File f (io/as-file f)]
    (when (.exists f)
      (doto (slurp f)
        ;; we keep json exactly as-is but send it through
        ;; read-write here simply as validation
        (-> parse write-str*)))))

(defn- parse-config
  ^Conf []
  (with-open [r (io/reader "conf/relay.yaml")]
    (conf/parse-conf r)))

(defn- execute!
  ;; Repl note: The arity fn here is good for repl invocation and returns jetty-server
  ;; which can be .stop/.start-ed. Also note that we have a logback-test.xml in
  ;; test/ classpath - which is nice to have picked up when on the repl classpath.
  ([] (execute! nil))
  ([sqlite-file-override]
   (let [conf (cond-> (parse-config)
                (some? sqlite-file-override) (assoc :sqlite-file sqlite-file-override))
         nip05-json (slurp-json* "conf/nip05.json")
         nip11-json (slurp-json* "conf/nip11.json")]
     (let [jetty-server (start-server! conf nip05-json nip11-json)]
       ;; Print human-readable configuration information and that our server has
       ;; started, before sleeping and blocking main thread forever.
       (log/info (str "\n" (conf/pretty* conf)))
       (log/infof "server started on port %d" (:http-port conf))
       jetty-server))))

(defn -main [& args]
  ;; This is the main entry point. The process is expected to be executed
  ;; within a working directory that contains a ./conf subdirectory, from
  ;; which we load the `relay.yaml` config file, nip05.json and nip11.json.
  ;;
  ;; Logging is via logback-classic; using a system property on the command-line
  ;; used to start this process, you can specify the logging configuration file
  ;; like so: "-Dlogback.configurationFile=conf/logback.xml".
  (let [jetty-server (execute! nil)]
    (log/info "server is started; main thread blocking forever...")
    (.join jetty-server)))
