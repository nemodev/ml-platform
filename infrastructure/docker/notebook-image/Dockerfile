FROM jupyter/scipy-notebook:python-3.11

USER root
RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-17-jre-headless curl \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN mkdir -p /opt/spark/jars /etc/spark/conf \
    && curl -fsSL -o /opt/spark/jars/hadoop-aws-3.3.4.jar \
      https://repo1.maven.org/maven2/org/apache/hadoop/hadoop-aws/3.3.4/hadoop-aws-3.3.4.jar \
    && curl -fsSL -o /opt/spark/jars/aws-java-sdk-bundle-1.12.262.jar \
      https://repo1.maven.org/maven2/com/amazonaws/aws-java-sdk-bundle/1.12.262/aws-java-sdk-bundle-1.12.262.jar

RUN cat > /etc/spark/conf/spark-defaults.conf <<'EOF'
spark.master                              k8s://https://kubernetes.default.svc
spark.executor.instances                  2
spark.executor.cores                      1
spark.executor.memory                     2g
spark.kubernetes.container.image          ml-platform-notebook:latest
spark.hadoop.fs.s3a.endpoint              http://minio.ml-platform.svc:9000
spark.hadoop.fs.s3a.path.style.access     true
spark.hadoop.fs.s3a.access.key            minioadmin
spark.hadoop.fs.s3a.secret.key            minioadmin
spark.hadoop.fs.s3a.connection.ssl.enabled false
spark.hadoop.fs.s3a.impl                  org.apache.hadoop.fs.s3a.S3AFileSystem
spark.jars                                /opt/spark/jars/hadoop-aws-3.3.4.jar,/opt/spark/jars/aws-java-sdk-bundle-1.12.262.jar
EOF

USER jovyan
COPY requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt

RUN cat > /opt/conda/bin/executor <<'EOF' \
    && chmod +x /opt/conda/bin/executor
#!/usr/bin/env bash
set -euo pipefail

SPARK_HOME=${SPARK_HOME:-/opt/conda/lib/python3.11/site-packages/pyspark}
SPARK_CLASS_BIN="${SPARK_HOME}/bin/spark-class"

# Spark on Kubernetes invokes executor containers with the "executor" command.
if [[ "${1:-executor}" == "executor" ]]; then
  shift || true
  java_opts=()
  while IFS='=' read -r name _; do
    java_opts+=("${!name}")
  done < <(env | grep '^SPARK_JAVA_OPT_' | sort -t_ -k4 -n)

  exec "${SPARK_CLASS_BIN}" \
    "${java_opts[@]}" \
    org.apache.spark.scheduler.cluster.k8s.KubernetesExecutorBackend \
    --driver-url "${SPARK_DRIVER_URL}" \
    --executor-id "${SPARK_EXECUTOR_ID}" \
    --cores "${SPARK_EXECUTOR_CORES}" \
    --app-id "${SPARK_APPLICATION_ID}" \
    --hostname "${SPARK_EXECUTOR_POD_IP}" \
    --resourceProfileId "${SPARK_RESOURCE_PROFILE_ID}"
fi

exec "$@"
EOF

ENV SPARK_CONF_DIR=/etc/spark/conf
