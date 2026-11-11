#!/usr/bin/env python3
"""模拟器实例供给服务（provisioner）。

设计目的
--------
前端「新建机库」需要真正拉起一个模拟器容器。让模拟器主容器直接持有 Docker
socket 等于把宿主机 root 交给一个 Web 应用；因此把容器创建能力隔离到这个独立
边车，并做三件事限制爆炸半径：

1. **不转发任意 Docker API**。只暴露 /create、/remove、/inspect、/health 四个接口，
   请求体是结构化字段而非原始 Docker JSON，服务端自己组装容器配置。
2. **镜像与能力白名单**。镜像必须是环境变量指定值（默认只允许模拟器镜像），
   容器只能加入指定网络，不挂载宿主机路径，不加 privileged，不暴露额外端口能力。
3. **参数强校验**。名称、端口、SN、坐标全部按正则/数值范围校验，容器名与卷名
   由服务端从名称派生，调用方无法指定任意容器名或卷名。

只依赖 Python 标准库 + Docker Engine API（unix socket），无需 pip 安装。
"""

import http.client
import json
import os
import re
import socket
import sys
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# ============================================================
# 配置（全部可通过环境变量覆盖）
# ============================================================

LISTEN_PORT = int(os.environ.get("PROVISIONER_PORT", "9500"))

# 允许创建的镜像白名单：逗号分隔。默认只有模拟器镜像。
ALLOWED_IMAGES = [
    image.strip()
    for image in os.environ.get(
        "PROVISIONER_ALLOWED_IMAGES", "hivemind-simulator-simulator:latest"
    ).split(",")
    if image.strip()
]

DEFAULT_IMAGE = os.environ.get(
    "PROVISIONER_DEFAULT_IMAGE", "hivemind-simulator-simulator:latest"
)

# 新实例加入的网络（通常是模拟器自己的 bridge 网络，便于用容器名互访）
NETWORK = os.environ.get("PROVISIONER_NETWORK", "hivemind-simulator_simulator-net")

# 共享素材卷（所有实例复用同一份照片/视频）
MEDIA_VOLUME = os.environ.get("PROVISIONER_MEDIA_VOLUME", "dji-dock-simulator-media")
VIDEO_VOLUME = os.environ.get("PROVISIONER_VIDEO_VOLUME", "dji-dock-simulator-videos")

# 默认注入的 MQTT 连接参数（新实例启动即用，避免落到模板默认账号）
DEFAULT_MQTT_HOST = os.environ.get("PROVISIONER_MQTT_HOST", "host.docker.internal")
DEFAULT_MQTT_PORT = os.environ.get("PROVISIONER_MQTT_PORT", "1883")
DEFAULT_MQTT_USERNAME = os.environ.get("PROVISIONER_MQTT_USERNAME", "")
DEFAULT_MQTT_PASSWORD = os.environ.get("PROVISIONER_MQTT_PASSWORD", "")

# 宿主机端口分配范围（避免与既有服务冲突）
PORT_MIN = int(os.environ.get("PROVISIONER_PORT_MIN", "9100"))
PORT_MAX = int(os.environ.get("PROVISIONER_PORT_MAX", "9199"))

DOCKER_SOCKET = os.environ.get("DOCKER_SOCKET", "/var/run/docker.sock")

# 容器名与卷名前缀
NAME_PREFIX = "dji-dock-simulator-"

# 校验规则
NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9-]{1,40}$")
SN_PATTERN = re.compile(r"^[0-9A-Za-z_-]{1,32}$")
# 机型枚举名（DJI SDK 的 DockModel/DroneModel/RcModel）：字母数字下划线
MODEL_KEY_PATTERN = re.compile(r"^[0-9A-Za-z_]{1,40}$")

# 容器启动等待上限（秒）：Spring Boot 冷启动通常 10~25 秒
READY_TIMEOUT_SECONDS = int(os.environ.get("PROVISIONER_READY_TIMEOUT", "90"))


# ============================================================
# Docker Engine API（unix socket，标准库实现）
# ============================================================

class UnixHTTPConnection(http.client.HTTPConnection):
    """通过 unix socket 与 Docker Engine 通信的极简 HTTP 连接。"""

    def __init__(self, socket_path: str, timeout: float = 60.0):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = socket_path

    def connect(self):
        sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        sock.settimeout(self.timeout)
        sock.connect(self.socket_path)
        self.sock = sock


class DockerAPI:
    """只封装本服务需要的几个端点，不做通用代理。"""

    def __init__(self, socket_path: str):
        self.socket_path = socket_path

    def _request(self, method: str, path: str, body=None, timeout: float = 60.0):
        conn = UnixHTTPConnection(self.socket_path, timeout=timeout)
        try:
            payload = None
            headers = {}
            if body is not None:
                payload = json.dumps(body).encode("utf-8")
                headers["Content-Type"] = "application/json"
                headers["Content-Length"] = str(len(payload))
            conn.request(method, path, body=payload, headers=headers)
            response = conn.getresponse()
            raw = response.read().decode("utf-8", "replace")
            return response.status, raw
        finally:
            conn.close()

    def create_container(self, name: str, config: dict):
        status, raw = self._request(
            "POST", f"/containers/create?name={urllib.parse.quote(name)}", config
        )
        return status, _safe_json(raw)

    def start_container(self, container_id: str):
        status, raw = self._request("POST", f"/containers/{container_id}/start")
        return status, raw

    def remove_container(self, name: str, force: bool = False):
        status, raw = self._request(
            "DELETE",
            f"/containers/{urllib.parse.quote(name)}?force={'true' if force else 'false'}",
        )
        return status, raw

    def inspect_container(self, name: str):
        status, raw = self._request(
            "GET", f"/containers/{urllib.parse.quote(name)}/json"
        )
        return status, _safe_json(raw)

    def inspect_image(self, image: str):
        tag, _, _ = image.partition(":") if ":" in image else (image, "", "latest")
        # 使用完整的 image 引用编码：registry 路径里的 / 必须保留
        encoded = urllib.parse.quote(image, safe="/:")
        status, raw = self._request("GET", f"/images/{encoded}/json")
        return status, _safe_json(raw)

    def list_containers(self, label_filter: str = ""):
        query = "?all=true"
        if label_filter:
            query += "&filters=" + urllib.parse.quote(
                json.dumps({"label": [label_filter]})
            )
        status, raw = self._request("GET", "/containers/json" + query)
        return status, _safe_json(raw)

    def create_volume(self, name: str):
        status, raw = self._request("POST", "/volumes/create", {"Name": name})
        return status, _safe_json(raw)

    def remove_volume(self, name: str):
        status, raw = self._request("DELETE", f"/volumes/{urllib.parse.quote(name)}")
        return status, raw


def _safe_json(raw: str):
    try:
        return json.loads(raw) if raw else {}
    except ValueError:
        return {"raw": raw}


# ============================================================
# 业务逻辑
# ============================================================

class ProvisionError(Exception):
    """参数或执行错误，映射为 HTTP 400/409。"""

    def __init__(self, message: str, status: int = 400):
        super().__init__(message)
        self.message = message
        self.status = status


def validate_create_request(payload: dict) -> dict:
    """校验并归一化创建请求，返回安全字段集合。"""
    if not isinstance(payload, dict):
        raise ProvisionError("请求体必须是 JSON 对象")

    name = str(payload.get("name", "")).strip().lower()
    if not NAME_PATTERN.match(name):
        raise ProvisionError(
            "name 必须是 2~41 位小写字母/数字/中划线，且以字母或数字开头"
        )

    image = str(payload.get("image") or DEFAULT_IMAGE).strip()
    if image not in ALLOWED_IMAGES:
        raise ProvisionError(
            f"镜像不在白名单内：{image}（允许：{', '.join(ALLOWED_IMAGES)}）"
        )

    host_port = payload.get("hostPort")
    if host_port is None:
        host_port = allocate_port()
    else:
        try:
            host_port = int(host_port)
        except (TypeError, ValueError):
            raise ProvisionError("hostPort 必须是整数")
        if not (PORT_MIN <= host_port <= PORT_MAX):
            raise ProvisionError(f"hostPort 必须在 {PORT_MIN}~{PORT_MAX} 之间")

    latitude = _optional_float(payload.get("latitude"), "latitude", -90, 90)
    longitude = _optional_float(payload.get("longitude"), "longitude", -180, 180)
    height = _optional_float(payload.get("height"), "height", -1000, 10000)

    dock_sn = _optional_sn(payload.get("dockSn"), "dockSn")
    drone_sn = _optional_sn(payload.get("droneSn"), "droneSn")
    # 接入模式（DOCK=机场上云 / PILOT=遥控器上云）：容器首启即带正确身份，
    # 避免"创建后还没 connect 就被重启"退化为机场模式
    device_mode = str(payload.get("deviceMode") or "DOCK").strip().upper()
    if device_mode not in ("DOCK", "PILOT"):
        raise ProvisionError("deviceMode 仅支持 DOCK / PILOT")
    controller_type = _optional_model_key(payload.get("controllerType"), "controllerType")
    controller_sn = _optional_sn(payload.get("controllerSn"), "controllerSn")

    return {
        "name": name,
        "image": image,
        "hostPort": host_port,
        "latitude": latitude,
        "longitude": longitude,
        "height": height,
        "dockSn": dock_sn,
        "droneSn": drone_sn,
        "deviceMode": device_mode,
        "controllerType": controller_type,
        "controllerSn": controller_sn,
        "mqttHost": str(payload.get("mqttHost") or DEFAULT_MQTT_HOST).strip(),
        "mqttPort": int(payload.get("mqttPort") or DEFAULT_MQTT_PORT),
        "mqttUsername": str(payload.get("mqttUsername") or DEFAULT_MQTT_USERNAME).strip(),
        "mqttPassword": str(payload.get("mqttPassword") or DEFAULT_MQTT_PASSWORD),
    }


def _optional_model_key(value, field: str):
    """机型枚举名（如 RC_PLUS / M350_RTK）：只校验字符集，具体型号由模拟器自己判断。"""
    if value is None or str(value).strip() == "":
        return None
    text = str(value).strip()
    if not MODEL_KEY_PATTERN.match(text):
        raise ProvisionError(f"{field} 仅允许字母/数字/下划线，长度 1~40")
    return text


def _optional_float(value, field: str, low: float, high: float):
    if value is None or value == "":
        return None
    try:
        number = float(value)
    except (TypeError, ValueError):
        raise ProvisionError(f"{field} 必须是数字")
    if not (low <= number <= high):
        raise ProvisionError(f"{field} 必须在 {low}~{high} 之间")
    return number


def _optional_sn(value, field: str):
    if value is None or str(value).strip() == "":
        return None
    text = str(value).strip()
    if not SN_PATTERN.match(text):
        raise ProvisionError(
            f"{field} 仅允许字母/数字/下划线/中划线，长度 1~32"
        )
    return text


_container_name = lambda name: NAME_PREFIX + name
_config_volume = lambda name: NAME_PREFIX + "config-" + name


_port_lock = threading.Lock()


def allocate_port(docker: DockerAPI = None) -> int:
    """在允许范围内挑一个尚未被占用的宿主机端口。"""
    docker = docker or DockerAPI(DOCKER_SOCKET)
    used = set()
    status, containers = docker.list_containers()
    if status == 200 and isinstance(containers, list):
        for item in containers:
            for port in item.get("Ports") or []:
                value = port.get("PublicPort")
                if value:
                    used.add(int(value))
    for port in range(PORT_MIN, PORT_MAX + 1):
        if port not in used:
            return port
    raise ProvisionError(
        f"端口范围内已无可用端口（{PORT_MIN}~{PORT_MAX}），请先清理旧实例", 409
    )


def build_container_config(spec: dict) -> dict:
    """按固定模板组装容器配置；所有可变项来自已校验的 spec。"""
    env = [
        "TZ=Asia/Shanghai",
        "JAVA_OPTS=-Xms256m -Xmx512m -Djava.net.preferIPv4Stack=true",
        "SERVER_PORT=9090",
        f"SIMULATOR_CONFIG_DIR=/app/config",
        f"SIMULATOR_MEDIA_MEDIADIR=/app/media",
        f"MQTT_HOST={spec['mqttHost']}",
        f"MQTT_PORT={spec['mqttPort']}",
    ]
    if spec["mqttUsername"]:
        env.append(f"MQTT_USERNAME={spec['mqttUsername']}")
    if spec["mqttPassword"]:
        env.append(f"MQTT_PASSWORD={spec['mqttPassword']}")
    # 未指定固定 SN 时启用唯一 SN 模式，避免多实例 topic 冲突
    # 遥控器上云（PILOT）：网关是遥控器，只有 drone SN 也需要唯一化，故两种模式都按"未固定即唯一"
    if not spec["dockSn"] and not spec["droneSn"]:
        env.append("SIMULATOR_SN_UNIQUEENABLED=true")
    # 接入模式与网关机型：容器首启即按清单登记的身份运行
    if spec["deviceMode"] and spec["deviceMode"] != "DOCK":
        env.append(f"SIMULATOR_DEVICE_MODE={spec['deviceMode']}")
    if spec["controllerType"]:
        env.append(f"SIMULATOR_CONTROLLER_TYPE={spec['controllerType']}")
    if spec["latitude"] is not None:
        env.append(f"SIMULATOR_LOCATION_LATITUDE={spec['latitude']}")
    if spec["longitude"] is not None:
        env.append(f"SIMULATOR_LOCATION_LONGITUDE={spec['longitude']}")
    if spec["height"] is not None:
        env.append(f"SIMULATOR_LOCATION_HEIGHT={spec['height']}")

    return {
        "Image": spec["image"],
        # 固定标签：用于识别"由控制台创建的实例"，删除时只允许操作这一类
        "Labels": {
            "hivemind.simulator.managed": "true",
            "hivemind.simulator.name": spec["name"],
        },
        "Env": env,
        "ExposedPorts": {"9090/tcp": {}},
        "HostConfig": {
            "PortBindings": {
                "9090/tcp": [{"HostIp": "0.0.0.0", "HostPort": str(spec["hostPort"])}]
            },
            "RestartPolicy": {"Name": "unless-stopped"},
            "ExtraHosts": ["host.docker.internal:host-gateway"],
            # 不挂载宿主机目录、不加特权、不用 host 网络
            "Privileged": False,
            "NetworkMode": NETWORK,
            "Binds": [
                f"{_config_volume(spec['name'])}:/app/config",
                f"{MEDIA_VOLUME}:/app/media",
                f"{VIDEO_VOLUME}:/app/videos",
            ],
            "LogConfig": {
                "Type": "json-file",
                "Config": {"max-size": "50m", "max-file": "3"},
            },
        },
        "Healthcheck": {
            "Test": [
                "CMD-SHELL",
                "curl -f -s http://localhost:9090/api/health | grep -q '\"status\":\"UP\"' || exit 1",
            ],
            "Interval": 30_000_000_000,
            "Timeout": 3_000_000_000,
            "StartPeriod": 30_000_000_000,
            "Retries": 3,
        },
    }


def wait_until_ready(container_name: str, timeout: int = READY_TIMEOUT_SECONDS) -> bool:
    """等待新实例健康。

    注意：这里<b>不能</b>用 http://127.0.0.1:{hostPort} 探测——本服务跑在容器里，
    而 hostPort 是宿主机映射端口，容器内部访问不到（同名陷阱我们在控制台侧也遇到过）。
    改为读 Docker 自己的健康检查状态，它是在容器内执行的，天然正确。
    """
    docker = DockerAPI(DOCKER_SOCKET)
    deadline = time.time() + timeout
    while time.time() < deadline:
        status, body = docker.inspect_container(container_name)
        if status == 200:
            state = body.get("State") or {}
            health = (state.get("Health") or {}).get("Status")
            if health == "healthy":
                return True
            if not state.get("Running"):
                return False
        time.sleep(2)
    return False


def create_instance(payload: dict) -> dict:
    docker = DockerAPI(DOCKER_SOCKET)
    spec = validate_create_request(payload)
    container_name = _container_name(spec["name"])

    # 幂等：同名容器已存在则直接返回，不重复创建
    status, existing = docker.inspect_container(container_name)
    if status == 200:
        port = _published_port(existing)
        return {
            "created": False,
            "containerName": container_name,
            "hostPort": port,
            "message": "实例已存在，复用现有容器",
        }
    if status != 404:
        raise ProvisionError(f"检查既有容器失败（HTTP {status}）", 502)

    # 镜像必须已在本地存在，避免本服务触发任意镜像拉取
    image_status, _ = docker.inspect_image(spec["image"])
    if image_status != 200:
        raise ProvisionError(
            f"镜像不存在：{spec['image']}（请在宿主机先 docker build）", 409
        )

    config_volume = _config_volume(spec["name"])
    docker.create_volume(config_volume)

    container_config = build_container_config(spec)
    create_status, create_body = docker.create_container(container_name, container_config)
    if create_status not in (201, 200):
        raise ProvisionError(
            f"创建容器失败：{create_body.get('message', create_body)}", 502
        )

    container_id = create_body.get("Id")
    start_status, start_raw = docker.start_container(container_id)
    if start_status not in (204, 304):
        docker.remove_container(container_name, force=True)
        raise ProvisionError(f"启动容器失败：{start_raw[:200]}", 502)

    ready = wait_until_ready(container_name)
    return {
        "created": True,
        "containerName": container_name,
        "containerId": container_id,
        "hostPort": spec["hostPort"],
        "ready": ready,
        "message": "实例已创建并启动" if ready else "容器已启动但健康检查未在超时内通过",
    }


def _published_port(inspect_body: dict):
    ports = (inspect_body.get("NetworkSettings") or {}).get("Ports") or {}
    for bindings in ports.values():
        for binding in bindings or []:
            value = binding.get("HostPort")
            if value:
                return int(value)
    return None


def remove_instance(name: str, remove_volume: bool = True) -> dict:
    if not NAME_PATTERN.match(name or ""):
        raise ProvisionError("name 参数非法")
    docker = DockerAPI(DOCKER_SOCKET)
    container_name = _container_name(name)

    status, existing = docker.inspect_container(container_name)
    if status == 404:
        return {"removed": False, "message": "实例不存在"}
    if status != 200:
        raise ProvisionError(f"检查容器失败（HTTP {status}）", 502)

    # 只允许删除由本服务创建的容器（标签校验）
    labels = ((existing.get("Config") or {}).get("Labels")) or {}
    if labels.get("hivemind.simulator.managed") != "true":
        raise ProvisionError(
            "该容器不是由控制台创建（缺少管理标签），拒绝删除", 403
        )

    docker.remove_container(container_name, force=True)
    if remove_volume:
        docker.remove_volume(_config_volume(name))
    return {"removed": True, "message": "实例已删除"}


def inspect_instance(name: str) -> dict:
    if not NAME_PATTERN.match(name or ""):
        raise ProvisionError("name 参数非法")
    docker = DockerAPI(DOCKER_SOCKET)
    status, body = docker.inspect_container(_container_name(name))
    if status == 404:
        return {"exists": False}
    if status != 200:
        raise ProvisionError(f"检查容器失败（HTTP {status}）", 502)
    state = body.get("State") or {}
    return {
        "exists": True,
        "containerName": _container_name(name),
        "status": state.get("Status"),
        "running": bool(state.get("Running")),
        "hostPort": _published_port(body),
    }


# ============================================================
# HTTP 服务
# ============================================================

class Handler(BaseHTTPRequestHandler):
    server_version = "FleetProvisioner/1.0"

    def log_message(self, fmt, *args):  # 精简日志，避免刷屏
        sys.stderr.write("[provisioner] " + fmt % args + "\n")

    def _respond(self, status: int, payload: dict):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self):
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        try:
            if parsed.path == "/health":
                self._respond(200, {
                    "status": "UP",
                    "service": "fleet-provisioner",
                    "network": NETWORK,
                    "portRange": [PORT_MIN, PORT_MAX],
                    "allowedImages": ALLOWED_IMAGES,
                })
                return
            if parsed.path == "/inspect":
                query = urllib.parse.parse_qs(parsed.query)
                name = (query.get("name") or [""])[0]
                self._respond(200, {"success": True, **inspect_instance(name)})
                return
            if parsed.path == "/ports":
                self._respond(200, {"success": True, "nextPort": allocate_port()})
                return
            self._respond(404, {"success": False, "message": "unknown path"})
        except ProvisionError as e:
            self._respond(e.status, {"success": False, "message": e.message})
        except Exception as e:  # noqa: BLE001 兜底，避免服务被单次异常打挂
            self._respond(500, {"success": False, "message": str(e)})

    def do_POST(self):
        parsed = urllib.parse.urlparse(self.path)
        try:
            if parsed.path == "/create":
                result = create_instance(self._read_json())
                self._respond(200, {"success": True, **result})
                return
            if parsed.path == "/remove":
                payload = self._read_json()
                result = remove_instance(
                    str(payload.get("name", "")),
                    bool(payload.get("removeVolume", True)),
                )
                self._respond(200, {"success": True, **result})
                return
            self._respond(404, {"success": False, "message": "unknown path"})
        except ProvisionError as e:
            self._respond(e.status, {"success": False, "message": e.message})
        except Exception as e:  # noqa: BLE001
            self._respond(500, {"success": False, "message": str(e)})


def main():
    server = ThreadingHTTPServer(("0.0.0.0", LISTEN_PORT), Handler)
    print(
        f"[provisioner] listening on :{LISTEN_PORT}, network={NETWORK}, "
        f"ports={PORT_MIN}-{PORT_MAX}, images={ALLOWED_IMAGES}",
        flush=True,
    )
    server.serve_forever()


if __name__ == "__main__":
    main()
