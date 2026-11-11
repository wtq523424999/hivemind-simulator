# ========================================
# 机库实例供给服务（provisioner）
# ========================================
# 独立边车容器，唯一职责是"按固定模板创建/删除模拟器实例容器"。
#
# 为什么单独一个容器：
#   模拟器主容器不挂载 Docker socket。网页要能真正拉起新实例，就必须有进程能访问
#   Docker Engine；把这能力隔离在边车里，主容器即使被攻破也碰不到宿主机。
#
# 限制：
#   - 只暴露 /health、/create、/remove、/inspect、/ports 五个接口，不代理任意 Docker API
#   - 镜像白名单（PROVISIONER_ALLOWED_IMAGES），且要求镜像已在宿主机存在，不触发拉取
#   - 容器只能加入指定 bridge 网络，不挂宿主机目录、不加 privileged、不用 host 网络
#   - 端口限制在 PROVISIONER_PORT_MIN~PROVISIONER_PORT_MAX
#   - 只允许删除带 hivemind.simulator.managed=true 标签的容器（即本服务自己创建的）
# ========================================

FROM m.daocloud.io/docker.io/library/python:3.12-slim

LABEL description="Hivemind Simulator 机库实例供给服务"
LABEL maintainer="CDMI"

WORKDIR /app

# 只依赖标准库，无需 pip install
COPY provisioner.py /app/provisioner.py

ENV PROVISIONER_PORT=9500 \
    PROVISIONER_NETWORK=hivemind-simulator_simulator-net \
    PROVISIONER_ALLOWED_IMAGES=hivemind-simulator-simulator:latest \
    PROVISIONER_DEFAULT_IMAGE=hivemind-simulator-simulator:latest \
    PROVISIONER_PORT_MIN=9100 \
    PROVISIONER_PORT_MAX=9199 \
    PROVISIONER_READY_TIMEOUT=90 \
    DOCKER_SOCKET=/var/run/docker.sock \
    PYTHONUNBUFFERED=1

EXPOSE 9500

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD python3 -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://127.0.0.1:9500/health', timeout=3).status==200 else 1)"

ENTRYPOINT ["python3", "/app/provisioner.py"]
