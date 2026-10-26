ARG ECR_REGISTRY=592754518446.dkr.ecr.us-west-2.amazonaws.com
ARG JAVA_VERSION=17

FROM ${ECR_REGISTRY}/shipper-java:${JAVA_VERSION} as app

ARG GIT_COMMIT=unspecified
LABEL git_commit=$GIT_COMMIT
ARG PROFILE
ARG PORT
ARG EXECUTABLE_JAR

RUN mkdir -p /var/app /var/data/app/logs

WORKDIR /var/app
ADD target/${EXECUTABLE_JAR}*.jar .
CMD ["/bin/sh", "-c" ,"java -Dspring.profiles.active=${PROFILE} -Dserver.port=${PORT} -jar ${EXECUTABLE_JAR}*.jar" ]
