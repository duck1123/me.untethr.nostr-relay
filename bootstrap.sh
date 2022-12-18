#!/usr/bin/env sh

java \
    -Dlogback.configurationFile=logback.xml \
    -cp me.untethr.nostr-relay.jar \
    clojure.main \
    -m me.untethr.nostr.app
