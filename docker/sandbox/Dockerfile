FROM node:24-bookworm-slim AS node
FROM maven:3.9-eclipse-temurin-21
LABEL io.jadecode.worker-protocol="1"
# 工具链在构建镜像时准备；普通任务保持非 root 和只读系统目录。
RUN apt-get update && apt-get install -y --no-install-recommends git python3 python3-venv ripgrep util-linux procps ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /usr/local/lib/node_modules/npm
RUN ln -s /usr/local/lib/node_modules/npm/bin/npm-cli.js /usr/local/bin/npm && ln -s /usr/local/lib/node_modules/npm/bin/npx-cli.js /usr/local/bin/npx
# Maven 合并镜像内全局 toolchains 与私有 HOME；宿主不向模型可写缓存内写配置。
RUN printf '%s' '<toolchains><toolchain><type>jdk</type><provides><version>21</version></provides><configuration><jdkHome>/opt/java/openjdk</jdkHome></configuration></toolchain></toolchains>' > /usr/share/maven/conf/toolchains.xml
COPY fetch-requirements.txt /opt/jadecode/fetch-requirements.txt
RUN python3 -m venv /opt/jadecode/fetch && /opt/jadecode/fetch/bin/pip install --no-cache-dir -r /opt/jadecode/fetch-requirements.txt
COPY jadecode.jar /opt/jadecode/worker.jar
COPY init.py /opt/jadecode/init.py
ENV HOME=/home/jade MAVEN_CONFIG=/home/jade/.m2
# 固定入口不解释模型正文；UID/GID 和资源限制由宿主的容器计划设置。
CMD ["python3", "/opt/jadecode/init.py"]
