# Earthfile
VERSION 0.6
ARG REPO=duck1123
ARG PROJECT=me.untethr.nostr-relay
ARG TAG=latest
ARG RELAY_VERSION=0.2.3-SNAPSHOT

ARG BUILD_IMAGE=circleci/clojure:tools-deps
ARG BUILD_IMAGE_USER=circleci
ARG USER_HOME=/home/${BUILD_IMAGE_USER}
ARG uid=3434
ARG gid=3434

IMPORT_JAR_DEPS:
  COMMAND
  # Store the files under the user
  COPY --dir \
       --chown=${BUILD_IMAGE_USER} \
    +jar-deps/.clojure \
    +jar-deps/.deps.clj \
    +jar-deps/.gitlibs \
    +jar-deps/.m2 \
    ${USER_HOME}
  # Store the files under root
  # COPY --dir \
  #      --chown=root \
  #   +jar-deps/.clojure \
  #   +jar-deps/.gitlibs \
  #   +jar-deps/.m2 \
  #   /root
  COPY --dir --chown=${BUILD_IMAGE_USER} +jar-deps/.cpcache .

# A base image others can be built off of
builder-base:
  FROM ${BUILD_IMAGE}
  WORKDIR /app
  COPY deps.edn Makefile .

jar-deps:
  FROM +builder-base
  RUN whoami
  USER root
  RUN rm -rf ${USER_HOME}/.m2
  RUN --mount=type=cache,target=/root/.clojure \
      --mount=type=cache,target=/root/.deps.clj \
      --mount=type=cache,target=/root/.gitlibs \
      --mount=type=cache,target=/root/.m2 \
    ( \
      clojure -Stree \
        && clojure -A:uberdeps -Stree \
        && clojure -A:test -Stree \
        && clojure -X:test \
    ) \
      && cp -r /root/.clojure ${USER_HOME}/ \
      && cp -r /root/.deps.clj ${USER_HOME}/ \
      && cp -r /root/.gitlibs ${USER_HOME}/ \
      && cp -r /root/.m2 ${USER_HOME}/
  USER ${uid}
  SAVE ARTIFACT ${USER_HOME}/.clojure
  SAVE ARTIFACT ${USER_HOME}/.deps.clj
  SAVE ARTIFACT ${USER_HOME}/.gitlibs
  SAVE ARTIFACT ${USER_HOME}/.m2
  SAVE ARTIFACT .cpcache

build-src:
  FROM +builder-base
  DO +IMPORT_JAR_DEPS
  COPY --dir conf src .

jar:
  FROM +build-src
  ARG RELAY_VERSION=${RELAY_VERSION}
  RUN echo "(ns me.untethr.nostr.version) (def version \"${RELAY_VERSION}\")" > src/me/untethr/nostr/version.clj
  RUN make uberjar
  SAVE ARTIFACT target/app.jar me.untethr.nostr-relay.jar

image:
  FROM amazoncorretto:19-alpine
  WORKDIR /app
  ARG EXPECTED_REF=${REPO}/${PROJECT}:${TAG}
  COPY \
    ( +jar/me.untethr.nostr-relay.jar --RELAY_VERSION ${RELAY_VERSION} ) \
    .
  COPY bootstrap.sh .
  COPY conf/logback.xml .
  CMD ["./bootstrap.sh"]
  SAVE IMAGE --push ${EXPECTED_REF}

test:
  FROM +build-src
  COPY --dir test .
  RUN make test

ci:
  BUILD +test
  BUILD +image
