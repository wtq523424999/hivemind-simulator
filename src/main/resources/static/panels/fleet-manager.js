/**
 * 机库群管理面板（多机库配置 / 管理 / 创建）
 *
 * 定位：本组件是"机库群控制台"，不改变单实例控制台的任何既有行为。
 * 每个机库仍是一个独立的模拟器进程（独立 config 卷、独立 MQTT 连接、独立 SN），
 * 本面板维护它们的清单、聚合实时状态、把操作代理到目标实例。
 *
 * 数据来源：
 *   GET    /api/fleet/instances          清单
 *   GET    /api/fleet/statuses           实时状态（并发拉取，含电量/在舱/任务占用）
 *   GET    /api/fleet/models             机型选项（由 SDK 枚举推导）
 *   GET    /api/fleet/candidates         按到目标点的距离给出候选机库排序
 *   POST   /api/fleet/instances          新增
 *   PUT    /api/fleet/instances/{id}     修改
 *   DELETE /api/fleet/instances/{id}     移除登记
 *   POST   /api/fleet/instances/{id}/actions  单实例动作
 *   POST   /api/fleet/batch              批量动作（同一动作并发到多台）
 *   POST   /api/fleet/bringup            启用工作流（每台内部按顺序执行多步）
 *   GET    /api/fleet/instances/export   导出清单 JSON
 *   POST   /api/fleet/instances/import   导入清单 JSON
 *   GET    /api/fleet/platform-seed      平台预置数据（dock/device + SQL）
 *   GET    /api/fleet/compose            生成 docker compose
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerFleetManagerPanel(app) 注册。
 */
function registerFleetManagerPanel(app) {
    const { ref, reactive, computed, onMounted, onUnmounted, inject } = Vue;

    app.component('fleet-manager', {
        setup() {
            const ctx = inject('ctx');
            const { fetchJSON } = ctx;
            const { ElMessage, ElMessageBox } = ElementPlus;

            // ===== 清单与状态 =====
            const loading = ref(false);
            const refreshing = ref(false);
            const instances = ref([]);
            const statuses = ref([]);
            const configPath = ref('');
            const models = reactive({ dockTypes: [], droneTypes: [], controllerTypes: [], deviceModes: [] });
            const lastRefresh = ref('');

            // ===== 表单 =====
            const showForm = ref(false);
            const editingId = ref('');
            const saving = ref(false);
            const form = reactive(emptyForm());

            // ===== 实例供给（自动创建容器）=====
            const provisionerStatus = reactive({ enabled: false, reachable: false });
            /** 新建时是否自动创建实例容器；仅在供给服务可用时可选 */
            const autoProvision = ref(false);
            /** 创建结果（含逐步明细），展示在表格下方的结果区 */
            const provisionResults = ref([]);

            async function loadProvisionerStatus() {
                const data = await fetchJSON('/api/fleet/provisioner');
                if (data && data.success) {
                    provisionerStatus.enabled = !!data.enabled;
                    provisionerStatus.reachable = !!data.reachable;
                }
            }

            function emptyForm() {
                return {
                    name: '',
                    host: '127.0.0.1',
                    port: 9091,
                    // 接入模式：DOCK=机库上云（机场为网关）/ PILOT=遥控器上云（遥控器为网关）
                    deviceMode: 'DOCK',
                    dockSn: '',
                    droneSn: '',
                    dockType: 'DOCK3',
                    droneType: 'M4TD',
                    controllerType: 'RC_PLUS',
                    controllerSn: '',
                    latitude: null,
                    longitude: null,
                    height: 500.0,
                    note: '',
                    enabled: true,
                    selfInstance: false,
                    // 「新建并启动」相关
                    autoRegister: true,
                    hostPort: null,
                };
            }

            function resetForm() {
                Object.assign(form, emptyForm());
            }

            // ===== 批量 =====
            const selectedIds = ref([]);
            const batchRunning = ref(false);
            const batchResults = ref([]);
            const batchLog = ref('');

            // ===== compose =====
            const showCompose = ref(false);
            const composeText = ref('');
            const composeLoading = ref(false);
            const composeForm = reactive({
                image: 'hivemind-simulator-simulator:latest',
                mqttHost: 'host.docker.internal',
                mqttPort: 1883,
                mqttUsername: '',
                mqttPassword: '',
                network: '',
            });

            // 状态按 id 建索引，表格行直接合并
            const statusMap = computed(() => {
                const map = {};
                (statuses.value || []).forEach((item) => { map[item.id] = item; });
                return map;
            });

            // 合并清单 + 状态，作为表格数据源
            const rows = computed(() => (instances.value || []).map((item) => {
                const live = statusMap.value[item.id] || {};
                // 模式以实例实时上报为准（清单登记只是"期望值"）
                const deviceMode = live.deviceMode || item.deviceMode || 'DOCK';
                const isPilot = String(deviceMode).toUpperCase() === 'PILOT';
                return {
                    ...item,
                    live,
                    deviceMode,
                    isPilot,
                    reachable: !!live.reachable,
                    online: !!live.online,
                    mqttConnected: !!live.mqttConnected,
                    powered: !!live.powered,
                    // 展示优先用远端实时值（实例可能启用了唯一 SN，真实 SN 由它自己生成）
                    effectiveDockSn: live.dockSn || item.dockSn || '-',
                    effectiveDroneSn: live.droneSn || item.droneSn || '-',
                    effectiveDockType: live.dockType || item.dockType || '-',
                    effectiveDroneType: live.droneType || item.droneType || '-',
                    effectiveControllerSn: live.controllerSn || item.controllerSn || '-',
                    effectiveControllerType: live.controllerType || item.controllerType || '-',
                    // 网关 = 谁在这个模式下对外代表这台设备
                    effectiveGatewaySn: live.gatewaySn
                        || (isPilot ? (live.controllerSn || item.controllerSn) : (live.dockSn || item.dockSn))
                        || '-',
                    liveLatitude: live.latitude != null ? live.latitude : item.latitude,
                    liveLongitude: live.longitude != null ? live.longitude : item.longitude,
                    liveHeight: live.height != null ? live.height : item.height,
                    // 选库依据：以下字段来自实例实时上报（旧实例可能为 null）
                    batteryPercent: live.batteryPercent != null ? live.batteryPercent : null,
                    droneInDock: live.droneInDock != null ? live.droneInDock : null,
                    droneActivated: live.droneActivated != null ? live.droneActivated : null,
                    taskActive: live.taskActive != null ? live.taskActive : null,
                    flightId: live.flightId || null,
                    dockModeCode: live.dockModeCode != null ? live.dockModeCode : null,
                };
            }));

            /** 单行是否可派遣（与后端 FleetInstanceStatus.dispatchable 同口径，仅用于展示）。 */
            function dispatchableOf(row) {
                if (!row.reachable || !row.online) return false;
                // 遥控器上云没有机库任务能力，平台无法派发 flighttask_*
                if (row.isPilot) return false;
                if (row.liveLatitude == null || row.liveLongitude == null) return false;
                if (row.taskActive === true) return false;
                if (row.droneInDock === false) return false;
                if (row.batteryPercent != null && row.batteryPercent < 30) return false;
                return true;
            }

            /** 不可派遣原因（展示用，与后端 rejectReason 文案一致）。 */
            function rejectReasonOf(row) {
                if (!row.reachable) return '实例不可达';
                if (!row.online) return '未上线';
                if (row.isPilot) return '遥控器上云无派发能力';
                if (row.liveLatitude == null || row.liveLongitude == null) return '未上报坐标';
                if (row.taskActive === true) return '正在执行任务';
                if (row.droneInDock === false) return '飞行器不在舱';
                if (row.batteryPercent != null && row.batteryPercent < 30) return '电量不足';
                return null;
            }

            /** 概览统计：机库（DOCK）与遥控器（PILOT）分开计数，避免"机库总数"误导。 */
            const summary = computed(() => {
                const list = rows.value;
                return {
                    total: list.length,
                    docks: list.filter((r) => !r.isPilot).length,
                    pilots: list.filter((r) => r.isPilot).length,
                    reachable: list.filter((r) => r.reachable).length,
                    online: list.filter((r) => r.online).length,
                    mqtt: list.filter((r) => r.mqttConnected).length,
                    dispatchable: list.filter(dispatchableOf).length,
                };
            });

            // ===== 选库排序（联调平台「按距离智能选库」）=====
            const showCandidates = ref(false);
            const candidateLoading = ref(false);
            const candidateTarget = reactive({ latitude: null, longitude: null, limit: 0 });
            const candidates = ref([]);
            const candidateError = ref('');

            async function loadCandidates() {
                if (candidateTarget.latitude == null || candidateTarget.longitude == null) {
                    ElMessage.warning('请填写目标点纬度和经度');
                    return;
                }
                candidateLoading.value = true;
                candidateError.value = '';
                try {
                    const params = new URLSearchParams({
                        latitude: String(candidateTarget.latitude),
                        longitude: String(candidateTarget.longitude),
                        limit: String(candidateTarget.limit || 0),
                    });
                    const data = await fetchJSON('/api/fleet/candidates?' + params.toString());
                    if (data && data.success) {
                        candidates.value = data.candidates || [];
                    } else {
                        candidates.value = [];
                        candidateError.value = (data && data.message) || '查询失败';
                    }
                } finally {
                    candidateLoading.value = false;
                }
            }

            /** 距离展示：<1km 用米，否则用公里。 */
            function distanceText(meters) {
                if (meters == null) return '-';
                return meters < 1000 ? `${meters} m` : `${(meters / 1000).toFixed(2)} km`;
            }

            // ===== 一键启用（顺序工作流）=====
            const bringupRunning = ref(false);
            const showBringupParams = ref(false);
            const bringupParams = reactive({
                mqttHost: '',
                mqttPort: null,
                mqttUsername: '',
                mqttPassword: '',
                organizationId: '',
                deviceBindingCode: '',
                skipRegister: false,
            });
            const bringupResults = ref([]);

            async function runBringup() {
                const ids = selectedIds.value.length ? selectedIds.value : null;
                const scope = ids ? `选中的 ${ids.length} 台` : `全部 ${summary.value.total} 台`;
                try {
                    await ElMessageBox.confirm(
                        `将对${scope}机库执行「一键启用」：\n开机 → 连接 MQTT → 上线 → 下发坐标。\n\n`
                        + '各步幂等（已满足会跳过），前一步失败会中止该台后续步骤。',
                        '一键启用确认',
                        { type: 'warning', confirmButtonText: '开始', cancelButtonText: '取消' }
                    );
                } catch (e) {
                    return;
                }
                await executeBringup(ids);
            }

            /** 单台启用（表格行内按钮）。 */
            async function runSingleBringup(row) {
                await executeBringup([row.id]);
            }

            /** 实际执行启用工作流。 */
            async function executeBringup(ids) {
                bringupRunning.value = true;
                try {
                    const body = { ids };
                    if (bringupParams.mqttHost) body.mqtt_host = bringupParams.mqttHost;
                    if (bringupParams.mqttPort) body.mqtt_port = bringupParams.mqttPort;
                    if (bringupParams.mqttUsername) body.mqtt_username = bringupParams.mqttUsername;
                    if (bringupParams.mqttPassword) body.mqtt_password = bringupParams.mqttPassword;
                    if (bringupParams.organizationId) body.organization_id = bringupParams.organizationId;
                    if (bringupParams.deviceBindingCode) body.device_binding_code = bringupParams.deviceBindingCode;
                    if (bringupParams.skipRegister) body.skip_register = true;

                    const data = await fetchJSON('/api/fleet/bringup', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(body),
                    });
                    if (data) {
                        bringupResults.value = data.results || [];
                        ElMessage[data.failed > 0 ? 'warning' : 'success'](
                            `一键启用：成功 ${data.ok} / 失败 ${data.failed}（共 ${data.total}）`);
                    }
                } finally {
                    bringupRunning.value = false;
                    await loadStatuses();
                }
            }

            // ===== 清单导入导出 =====
            const importFileInput = ref(null);
            const importing = ref(false);

            function exportInstances() {
                window.open('/api/fleet/instances/export', '_blank');
            }

            function triggerImport() {
                importFileInput.value?.click();
            }

            async function onImportFileSelected(event) {
                const file = event.target.files && event.target.files[0];
                if (!file) return;
                importing.value = true;
                try {
                    const text = await file.text();
                    let payload;
                    try {
                        payload = JSON.parse(text);
                    } catch (e) {
                        ElMessage.error('文件不是合法 JSON：' + e.message);
                        return;
                    }
                    // 允许直接传 fleet.json（{version,instances}）或裸数组
                    const config = Array.isArray(payload)
                        ? { version: 1, instances: payload }
                        : payload;
                    let replace = true;
                    try {
                        await ElMessageBox.confirm(
                            '导入方式：\n· 点击「整表替换」= 清空现有清单后导入\n· 点击「合并」= 保留现有条目，按 id 覆盖/追加',
                            '导入机库清单',
                            { type: 'info', confirmButtonText: '整表替换', cancelButtonText: '合并',
                              distinguishCancelAndClose: true }
                        );
                    } catch (action) {
                        if (action === 'cancel') {
                            replace = false;
                        } else {
                            return; // 关闭对话框 = 放弃导入
                        }
                    }
                    const data = await fetchJSON(
                        `/api/fleet/instances/import?replace=${replace}`,
                        { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(config) });
                    if (data && data.success) {
                        const rejected = data.rejected || [];
                        ElMessage.success(`导入 ${data.imported} 条，共 ${data.total} 条`
                            + (rejected.length ? `，${rejected.length} 条被拒绝` : ''));
                        if (rejected.length) {
                            ElMessageBox.alert(rejected.join('\n'), '以下条目未导入', { type: 'warning' });
                        }
                        await Promise.all([loadInstances(), loadStatuses()]);
                    } else {
                        ElMessage.error((data && data.message) || '导入失败');
                    }
                } finally {
                    importing.value = false;
                    event.target.value = '';
                }
            }

            // ===== 平台预置导出 =====
            const showPlatformSeed = ref(false);
            const platformSeedLoading = ref(false);
            const platformSeed = reactive({ organizationId: '', brand: 'DJI' });
            const platformSeedData = reactive({ docks: [], devices: [], sql: '', skipped: [] });

            async function loadPlatformSeed() {
                platformSeedLoading.value = true;
                try {
                    const params = new URLSearchParams();
                    if (platformSeed.organizationId) params.set('organizationId', platformSeed.organizationId);
                    if (platformSeed.brand) params.set('brand', platformSeed.brand);
                    const data = await fetchJSON('/api/fleet/platform-seed?' + params.toString());
                    if (data && data.success) {
                        platformSeedData.docks = data.docks || [];
                        platformSeedData.devices = data.devices || [];
                        platformSeedData.sql = data.sql || '';
                        platformSeedData.skipped = data.skipped || [];
                        if (!platformSeed.organizationId && data.organizationId) {
                            platformSeed.organizationId = data.organizationId;
                        }
                    }
                } finally {
                    platformSeedLoading.value = false;
                }
            }

            async function openPlatformSeed() {
                showPlatformSeed.value = true;
                await loadPlatformSeed();
            }

            async function copyPlatformSql() {
                try {
                    await navigator.clipboard.writeText(platformSeedData.sql);
                    ElMessage.success('SQL 已复制');
                } catch (e) {
                    ElMessage.warning('浏览器拒绝访问剪贴板，请手动全选复制');
                }
            }

            function downloadPlatformSql() {
                const params = new URLSearchParams({ download: 'true' });
                if (platformSeed.organizationId) params.set('organizationId', platformSeed.organizationId);
                if (platformSeed.brand) params.set('brand', platformSeed.brand);
                window.open('/api/fleet/platform-seed.sql?' + params.toString(), '_blank');
            }

            // ===== 数据加载 =====
            async function loadInstances() {
                const data = await fetchJSON('/api/fleet/instances');
                if (data && data.success) {
                    instances.value = data.instances || [];
                    configPath.value = data.configPath || '';
                }
            }

            async function loadStatuses() {
                refreshing.value = true;
                try {
                    const data = await fetchJSON('/api/fleet/statuses');
                    if (data && data.success) {
                        statuses.value = data.statuses || [];
                        lastRefresh.value = new Date().toLocaleTimeString();
                    }
                } finally {
                    refreshing.value = false;
                }
            }

            async function loadModels() {
                const data = await fetchJSON('/api/fleet/models');
                if (data && data.success) {
                    models.dockTypes = data.dockTypes || [];
                    models.droneTypes = data.droneTypes || [];
                    models.controllerTypes = data.controllerTypes || [];
                    models.deviceModes = data.deviceModes || [];
                }
            }

            async function refreshAll() {
                loading.value = true;
                try {
                    await Promise.all([loadInstances(), loadModels(), loadProvisionerStatus()]);
                    await loadStatuses();
                } finally {
                    loading.value = false;
                }
            }

            // ===== 表单操作 =====
            function openCreate() {
                resetForm();
                editingId.value = '';
                // 端口默认取"当前清单里最大端口 + 1"，避免用户手工撞端口
                const maxPort = (instances.value || []).reduce((max, item) => Math.max(max, item.port || 0), 9090);
                form.port = maxPort + 1;
                // 供给服务可用时默认走「新建并启动」——这正是用户期望的一步到位
                autoProvision.value = provisionerStatus.reachable;
                showForm.value = true;
            }

            function openEdit(row) {
                editingId.value = row.id;
                Object.assign(form, {
                    name: row.name || '',
                    host: row.host || '127.0.0.1',
                    port: row.port || 9090,
                    deviceMode: row.deviceMode || 'DOCK',
                    dockSn: row.dockSn || '',
                    droneSn: row.droneSn || '',
                    dockType: row.dockType || 'DOCK3',
                    droneType: row.droneType || 'M4TD',
                    controllerType: row.controllerType || 'RC_PLUS',
                    controllerSn: row.controllerSn || '',
                    latitude: row.latitude,
                    longitude: row.longitude,
                    height: row.height,
                    note: row.note || '',
                    enabled: row.enabled !== false,
                    selfInstance: !!row.selfInstance,
                });
                showForm.value = true;
            }

            // ===== 接入模式联动（遥控器上云）=====
            /** 当前遥控器可搭配的飞行器（来自 SDK 搭配表，后端下发，不在前端另存静态表）。 */
            const formDroneTypeOptions = computed(() => {
                if (form.deviceMode !== 'PILOT') {
                    return models.droneTypes;
                }
                const rc = models.controllerTypes.find((item) => item.value === form.controllerType);
                if (!rc || !rc.droneKeys || rc.droneKeys.length === 0) {
                    return models.droneTypes;
                }
                return models.droneTypes.filter((item) => rc.droneKeys.includes(item.value));
            });

            /** 切换接入模式：把机型默认值调整为该模式下的合理初值。 */
            function onFormDeviceModeChange() {
                if (form.deviceMode === 'PILOT') {
                    if (!form.controllerType) {
                        form.controllerType = 'RC_PLUS';
                    }
                    const compatible = formDroneTypeOptions.value;
                    if (compatible.length && !compatible.some((item) => item.value === form.droneType)) {
                        form.droneType = compatible[0].value;
                    }
                } else {
                    if (!form.dockType) {
                        form.dockType = 'DOCK3';
                    }
                    if (!form.droneType) {
                        form.droneType = 'M4TD';
                    }
                }
            }

            /** 切换遥控器机型：飞行器不兼容时自动切到该遥控器首个兼容机型（与单兵面板一致）。 */
            function onFormControllerTypeChange() {
                const compatible = formDroneTypeOptions.value;
                if (compatible.length && !compatible.some((item) => item.value === form.droneType)) {
                    form.droneType = compatible[0].value;
                }
            }

            async function saveForm() {
                if (!form.name || !form.name.trim()) {
                    ElMessage.warning('请填写设备名称');
                    return;
                }
                // 「新建并启动」由供给服务创建容器，地址由后端自动回填，不要求填 host
                if (!autoProvision.value && (!form.host || !form.host.trim())) {
                    ElMessage.warning('请填写控制台地址');
                    return;
                }
                saving.value = true;
                try {
                    if (!editingId.value && autoProvision.value) {
                        await submitProvision();
                        return;
                    }
                    // 只提交当前模式相关的身份字段：切到 PILOT 后不应把旧机场 SN 继续写进清单
                    const payload = {
                        ...form,
                        name: form.name.trim(),
                        host: form.host.trim(),
                        dockType: form.deviceMode === 'PILOT' ? null : (form.dockType || null),
                        controllerType: form.deviceMode === 'PILOT' ? (form.controllerType || null) : null,
                        controllerSn: form.deviceMode === 'PILOT' ? (form.controllerSn || null) : null,
                    };
                    const url = editingId.value
                        ? `/api/fleet/instances/${editingId.value}`
                        : '/api/fleet/instances';
                    const data = await fetchJSON(url, {
                        method: editingId.value ? 'PUT' : 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify(payload),
                    });
                    if (data && data.success) {
                        ElMessage.success(editingId.value ? '设备已更新' : '设备已创建');
                        showForm.value = false;
                        await Promise.all([loadInstances(), loadStatuses()]);
                    } else {
                        ElMessage.error((data && data.message) || '保存失败');
                    }
                } finally {
                    saving.value = false;
                }
            }

            /**
             * 新建并启动：登记 → 创建实例容器 → 连接 MQTT → 注册上线。
             * 后端一次调用完成，返回逐步明细，这里直接展示在结果区。
             */
            async function submitProvision() {
                const payload = {
                    name: form.name.trim(),
                    // host/port 在自动创建时由后端回填，先传占位避免校验失败
                    host: form.host && form.host.trim() ? form.host.trim() : '127.0.0.1',
                    port: form.port || 9090,
                    deviceMode: form.deviceMode || 'DOCK',
                    dockSn: form.dockSn || null,
                    droneSn: form.droneSn || null,
                    dockType: form.deviceMode === 'PILOT' ? null : (form.dockType || null),
                    droneType: form.droneType || null,
                    controllerType: form.deviceMode === 'PILOT' ? (form.controllerType || null) : null,
                    controllerSn: form.deviceMode === 'PILOT' ? (form.controllerSn || null) : null,
                    latitude: form.latitude,
                    longitude: form.longitude,
                    height: form.height,
                    note: form.note || null,
                    enabled: form.enabled !== false,
                    autoProvision: true,
                    autoRegister: form.autoRegister !== false,
                    hostPort: form.hostPort || null,
                    mqttHost: bringupParams.mqttHost || null,
                    mqttPort: bringupParams.mqttPort || null,
                    mqttUsername: bringupParams.mqttUsername || null,
                    mqttPassword: bringupParams.mqttPassword || null,
                    organization_id: bringupParams.organizationId || null,
                    device_binding_code: bringupParams.deviceBindingCode || null,
                };
                const data = await fetchJSON('/api/fleet/instances/provision', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(payload),
                });
                if (data) {
                    provisionResults.value = [{
                        id: data.id,
                        name: form.name,
                        success: data.success,
                        message: data.message,
                        containerName: data.containerName,
                        hostPort: data.hostPort,
                        steps: data.steps || [],
                    }];
                    const ok = data.success;
                    ElMessage[ok ? 'success' : 'warning'](
                        (ok ? '机库已创建：' : '部分步骤未完成：') + (data.message || ''));
                    if (ok) {
                        showForm.value = false;
                    }
                }
                await Promise.all([loadInstances(), loadStatuses()]);
            }

            async function removeInstance(row) {
                // 供给服务可用时，提供"连容器一起删"的选项
                let removeContainer = false;
                if (provisionerStatus.reachable) {
                    try {
                        await ElMessageBox.confirm(
                            `确定移除「${row.name}」吗？\n\n`
                            + '· 点击「连容器一起删」= 删除登记 + 停止并删除该实例容器（含 config 卷）\n'
                            + '· 点击「仅移除登记」= 只从清单删掉，容器继续运行',
                            '移除机库',
                            { type: 'warning', confirmButtonText: '连容器一起删',
                              cancelButtonText: '仅移除登记', distinguishCancelAndClose: true }
                        );
                        removeContainer = true;
                    } catch (action) {
                        if (action === 'cancel') {
                            removeContainer = false;
                        } else {
                            return; // 关闭对话框 = 放弃
                        }
                    }
                } else {
                    try {
                        await ElMessageBox.confirm(
                            `确定从清单中移除「${row.name}」吗？\n\n只删除登记记录，不会停止该实例的进程。`,
                            '移除机库',
                            { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }
                        );
                    } catch (e) {
                        return;
                    }
                }
                const url = `/api/fleet/instances/${row.id}?removeContainer=${removeContainer}`;
                const data = await fetchJSON(url, { method: 'DELETE' });
                if (data && data.success) {
                    ElMessage.success(removeContainer && data.containerRemoved
                        ? '已移除登记并删除容器'
                        : '已移除登记');
                    await Promise.all([loadInstances(), loadStatuses()]);
                } else {
                    ElMessage.error((data && data.message) || '移除失败');
                }
            }

            // ===== 动作 =====
            async function runAction(row, action, params) {
                const body = { action, ...(params || {}) };
                const data = await fetchJSON(`/api/fleet/instances/${row.id}/actions`, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body),
                });
                if (data && data.success) {
                    ElMessage.success(`【${row.name}】${data.message || '操作成功'}`);
                } else {
                    ElMessage.error(`【${row.name}】${(data && data.message) || '操作失败'}`);
                }
                await loadStatuses();
            }

            async function runBatch(action) {
                const ids = selectedIds.value.length ? selectedIds.value : null;
                const actionLabel = batchActionLabel(action);
                try {
                    await ElMessageBox.confirm(
                        ids
                            ? `将对选中的 ${ids.length} 台机库执行「${actionLabel}」。`
                            : `将对全部 ${summary.value.total} 台机库执行「${actionLabel}」。`,
                        '批量操作确认',
                        { type: 'warning', confirmButtonText: '执行', cancelButtonText: '取消' }
                    );
                } catch (e) {
                    return;
                }
                batchRunning.value = true;
                batchLog.value = `执行中：${actionLabel} ...`;
                try {
                    const data = await fetchJSON('/api/fleet/batch', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ action, ids }),
                    });
                    if (data) {
                        batchResults.value = data.results || [];
                        batchLog.value = `${actionLabel}：成功 ${data.ok} / 失败 ${data.failed}（共 ${data.total}）`;
                        ElMessage[data.failed > 0 ? 'warning' : 'success'](batchLog.value);
                    }
                } finally {
                    batchRunning.value = false;
                    await loadStatuses();
                }
            }

            function batchActionLabel(action) {
                return {
                    power_on: '开机',
                    power_off: '关机下线',
                    connect: '连接 MQTT',
                    online: '上线',
                    offline: '下线',
                    set_location: '下发坐标',
                    apply_identity: '下发身份与坐标',
                }[action] || action;
            }

            // ===== compose 生成 =====
            async function openCompose() {
                showCompose.value = true;
                await loadCompose();
            }

            async function loadCompose() {
                composeLoading.value = true;
                try {
                    const params = new URLSearchParams();
                    Object.entries(composeForm).forEach(([key, value]) => {
                        if (value !== null && value !== undefined && String(value).trim() !== '') {
                            params.set(key, value);
                        }
                    });
                    const resp = await fetch(`/api/fleet/compose?${params.toString()}`);
                    composeText.value = await resp.text();
                } catch (e) {
                    composeText.value = '生成失败：' + e.message;
                } finally {
                    composeLoading.value = false;
                }
            }

            async function copyCompose() {
                try {
                    await navigator.clipboard.writeText(composeText.value);
                    ElMessage.success('已复制到剪贴板');
                } catch (e) {
                    ElMessage.warning('浏览器拒绝访问剪贴板，请手动全选复制');
                }
            }

            function downloadCompose() {
                const blob = new Blob([composeText.value], { type: 'text/yaml;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const link = document.createElement('a');
                link.href = url;
                link.download = 'docker-compose-fleet.yml';
                link.click();
                URL.revokeObjectURL(url);
            }

            // ===== 格式化 =====
            function coordText(lat, lng) {
                if (lat == null || lng == null) {
                    return '未配置';
                }
                return `${Number(lat).toFixed(6)}, ${Number(lng).toFixed(6)}`;
            }

            function heightText(height) {
                return height == null ? '-' : `${Number(height).toFixed(1)} m`;
            }

            function stateTagType(row) {
                if (!row.reachable) return 'info';
                if (row.online) return 'success';
                if (row.powered) return 'warning';
                return 'info';
            }

            function stateText(row) {
                if (!row.reachable) return '不可达';
                if (row.online) return '在线';
                if (row.powered) return '已开机';
                return '离线';
            }

            /**
             * 浏览器侧的控制台入口：走当前控制台的同源代理，而不是清单里登记的内部地址。
             *
             * 登记项里的 host/port 是服务端视角的地址（容器部署时是「容器名:9090」），
             * 浏览器解析不了 Docker 内部主机名；其他实例的宿主机端口对外也通常没有映射。
             * /fleet/{id}/ 由后端反向代理到目标实例，因此从哪个入口（9090/9513/域名）访问都可用。
             */
            function consoleLink(row) {
                return `/fleet/${encodeURIComponent(row.id)}/`;
            }

            /** 打开目标实例控制台（同源代理，新标签页）。 */
            function openConsole(row) {
                window.open(consoleLink(row), '_blank');
            }

            async function openRegistry(row) {
                if (row.live.self) {
                    ElMessage.info('这是当前控制台本身，请直接使用页头的「注册到第三方平台」');
                    return;
                }
                window.open(consoleLink(row), '_blank');
            }

            // ===== 定时刷新 =====
            let timer = null;
            onMounted(async () => {
                await refreshAll();
                timer = setInterval(() => {
                    // 面板不可见时不刷，避免后台标签页持续打请求
                    if (document.visibilityState === 'visible') {
                        loadStatuses();
                    }
                }, 5000);
            });

            onUnmounted(() => {
                if (timer) clearInterval(timer);
            });

            return {
                // 状态
                loading, refreshing, instances, statuses, rows, summary, configPath, lastRefresh,
                models, showForm, editingId, saving, form,
                formDroneTypeOptions, onFormDeviceModeChange, onFormControllerTypeChange,
                provisionerStatus, autoProvision, provisionResults, submitProvision,
                selectedIds, batchRunning, batchResults, batchLog,
                showCompose, composeText, composeLoading, composeForm,
                // 选库排序
                showCandidates, candidateLoading, candidateTarget, candidates, candidateError,
                loadCandidates, distanceText,
                // 一键启用
                bringupRunning, showBringupParams, bringupParams, bringupResults,
                runBringup, runSingleBringup,
                // 导入导出
                importFileInput, importing, exportInstances, triggerImport, onImportFileSelected,
                // 平台预置
                showPlatformSeed, platformSeedLoading, platformSeed, platformSeedData,
                openPlatformSeed, loadPlatformSeed, copyPlatformSql, downloadPlatformSql,
                // 方法
                refreshAll, loadStatuses, openCreate, openEdit, saveForm, resetForm, removeInstance,
                runAction, runBatch, batchActionLabel,
                openCompose, loadCompose, copyCompose, downloadCompose,
                coordText, heightText, stateTagType, stateText, openConsole, openRegistry, consoleLink,
                dispatchableOf, rejectReasonOf,
            };
        },

        template: `
            <div class="fleet-manager">
                <!-- 概览 -->
                <div class="fleet-summary">
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">设备总数</span>
                        <span class="fleet-summary-value">{{ summary.total }}</span>
                    </div>
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">机库 / 遥控器</span>
                        <span class="fleet-summary-value">{{ summary.docks }} / {{ summary.pilots }}</span>
                    </div>
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">可达</span>
                        <span class="fleet-summary-value">{{ summary.reachable }}</span>
                    </div>
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">已上线</span>
                        <span class="fleet-summary-value">{{ summary.online }}</span>
                    </div>
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">MQTT 已连</span>
                        <span class="fleet-summary-value">{{ summary.mqtt }}</span>
                    </div>
                    <div class="fleet-summary-item">
                        <span class="fleet-summary-label">可派遣</span>
                        <span class="fleet-summary-value" style="color:#10b981;">{{ summary.dispatchable }}</span>
                    </div>
                    <div class="fleet-summary-spacer"></div>
                    <span class="fleet-refresh-hint">
                        {{ refreshing ? '刷新中...' : (lastRefresh ? '最后刷新 ' + lastRefresh : '') }}
                    </span>
                    <el-button size="small" @click="loadStatuses" :loading="refreshing">刷新状态</el-button>
                </div>

                <!-- 工具栏 -->
                <div class="fleet-toolbar">
                    <el-button type="primary" size="small" @click="openCreate">新增设备</el-button>
                    <el-tag v-if="provisionerStatus.reachable" size="small" type="success" style="margin-left:2px;">
                        可自动创建实例
                    </el-tag>
                    <el-tag v-else-if="provisionerStatus.enabled" size="small" type="danger" style="margin-left:2px;">
                        供给服务不可达
                    </el-tag>
                    <el-tag v-else size="small" type="info" style="margin-left:2px;">
                        未部署供给服务
                    </el-tag>
                    <el-button type="success" size="small" @click="runBringup" :loading="bringupRunning"
                               title="按顺序执行：开机 → 连接 MQTT → 上线 → 下发坐标（各步幂等）">一键启用</el-button>
                    <el-button size="small" text @click="showBringupParams = !showBringupParams">参数</el-button>
                    <el-divider direction="vertical"></el-divider>
                    <el-button size="small" @click="runBatch('power_on')" :loading="batchRunning">批量开机</el-button>
                    <el-button size="small" @click="runBatch('connect')" :loading="batchRunning">批量连接</el-button>
                    <el-button size="small" @click="runBatch('online')" :loading="batchRunning">批量上线</el-button>
                    <el-button size="small" @click="runBatch('apply_identity')" :loading="batchRunning">下发身份/坐标</el-button>
                    <el-button size="small" @click="runBatch('offline')" :loading="batchRunning">批量下线</el-button>
                    <div class="fleet-toolbar-spacer"></div>
                    <el-button size="small" @click="showCandidates = true">选库排序</el-button>
                    <el-button size="small" @click="openPlatformSeed">平台预置</el-button>
                    <el-button size="small" @click="openCompose">生成编排</el-button>
                    <el-button size="small" text @click="exportInstances">导出清单</el-button>
                    <el-button size="small" text @click="triggerImport" :loading="importing">导入清单</el-button>
                    <input ref="importFileInput" type="file" accept=".json,application/json"
                           style="display:none" @change="onImportFileSelected">
                    <span class="fleet-selection-hint" v-if="selectedIds.length">已选 {{ selectedIds.length }} 台</span>
                </div>

                <!-- 一键启用参数（连接覆盖，留空则用各实例现有配置） -->
                <div class="fleet-bringup-params" v-if="showBringupParams">
                    <el-form label-width="96px" size="small" inline>
                        <el-form-item label="MQTT 地址">
                            <el-input v-model="bringupParams.mqttHost" placeholder="留空用实例现有配置"
                                      style="width:200px;"></el-input>
                        </el-form-item>
                        <el-form-item label="端口">
                            <el-input-number v-model="bringupParams.mqttPort" :min="1" :max="65535"
                                             controls-position="right" style="width:110px;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="用户名">
                            <el-input v-model="bringupParams.mqttUsername" style="width:130px;"></el-input>
                        </el-form-item>
                        <el-form-item label="密码">
                            <el-input v-model="bringupParams.mqttPassword" show-password style="width:130px;"></el-input>
                        </el-form-item>
                        <el-form-item label="组织 ID">
                            <el-input v-model="bringupParams.organizationId" style="width:150px;"></el-input>
                        </el-form-item>
                        <el-form-item label="绑定码">
                            <el-input v-model="bringupParams.deviceBindingCode" style="width:110px;"></el-input>
                        </el-form-item>
                        <el-form-item label="跳过注册">
                            <el-switch v-model="bringupParams.skipRegister"></el-switch>
                        </el-form-item>
                    </el-form>
                    <div class="fleet-form-hint">
                        连接参数只需在首次启用时填写；已注册的设备重连可打开「跳过注册」。
                    </div>
                </div>

                <!-- 表 -->
                <el-table :data="rows" size="small" border stripe
                          :row-class-name="({ row }) => row.reachable ? '' : 'fleet-row-unreachable'"
                          style="width:100%"
                          @selection-change="val => { selectedIds = val.map(v => v.id) }">
                    <el-table-column type="selection" width="42"></el-table-column>
                    <el-table-column label="设备" min-width="160">
                        <template #default="{ row }">
                            <div class="fleet-name">
                                {{ row.name }}
                                <el-tag v-if="row.selfInstance || (row.live && row.live.self)" size="small" type="primary" style="margin-left:6px;">本机</el-tag>
                            </div>
                            <div class="fleet-sub">
                                <el-tag size="small" :type="row.isPilot ? 'warning' : 'success'" style="margin-right:4px;">
                                    {{ row.isPilot ? '遥控器上云' : '机库上云' }}
                                </el-tag>
                            </div>
                            <div class="fleet-sub" :title="'内部地址：' + (row.consoleUrl || row.live.consoleUrl)">
                                {{ consoleLink(row) }}
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column label="状态" width="180">
                        <template #default="{ row }">
                            <div>
                                <el-tag size="small" :type="stateTagType(row)">{{ stateText(row) }}</el-tag>
                                <el-tag v-if="!row.isPilot" size="small" :type="dispatchableOf(row) ? 'success' : 'info'"
                                        style="margin-left:4px;">
                                    {{ dispatchableOf(row) ? '可派遣' : '不可派遣' }}
                                </el-tag>
                            </div>
                            <div class="fleet-sub" style="margin-top:2px;">
                                MQTT {{ row.mqttConnected ? '已连' : '未连' }}
                                · 电量 {{ row.batteryPercent != null ? row.batteryPercent + '%' : '-' }}
                            </div>
                            <div class="fleet-sub" v-if="row.taskActive === true" style="color:#e6a23c;">
                                执行任务中
                            </div>
                            <div class="fleet-sub" v-else-if="!row.isPilot && rejectReasonOf(row) && row.reachable">
                                {{ rejectReasonOf(row) }}
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column label="网关 SN / 机型" min-width="160">
                        <template #default="{ row }">
                            <div class="fleet-mono">{{ row.effectiveGatewaySn }}</div>
                            <div class="fleet-sub">
                                {{ row.isPilot ? row.effectiveControllerType : row.effectiveDockType }}
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column label="飞行器 SN" min-width="150">
                        <template #default="{ row }">
                            <div class="fleet-mono">{{ row.effectiveDroneSn }}</div>
                            <div class="fleet-sub">
                                {{ row.effectiveDroneType }}
                                · {{ row.droneInDock === true ? '在舱' : (row.droneInDock === false ? '不在舱' : '舱位未知') }}
                            </div>
                        </template>
                    </el-table-column>
                    <el-table-column label="坐标（纬度, 经度）" min-width="165">
                        <template #default="{ row }">
                            <div class="fleet-mono">{{ coordText(row.liveLatitude, row.liveLongitude) }}</div>
                            <div class="fleet-sub">海拔 {{ heightText(row.liveHeight) }}</div>
                        </template>
                    </el-table-column>
                    <el-table-column label="操作" width="230" fixed="right">
                        <template #default="{ row }">
                            <el-button size="small" text type="primary" @click="openConsole(row)">控制台</el-button>
                            <el-button size="small" text type="success" @click="runSingleBringup(row)">启用</el-button>
                            <el-dropdown size="small" trigger="click"
                                         @command="cmd => runAction(row, cmd)" style="margin-left:8px;">
                                <el-button size="small" text>更多 ▾</el-button>
                                <template #dropdown>
                                    <el-dropdown-menu>
                                        <el-dropdown-item command="power_on">开机</el-dropdown-item>
                                        <el-dropdown-item command="connect">连接 MQTT</el-dropdown-item>
                                        <el-dropdown-item command="online">上线</el-dropdown-item>
                                        <el-dropdown-item command="offline" divided>下线</el-dropdown-item>
                                    </el-dropdown-menu>
                                </template>
                            </el-dropdown>
                        </template>
                    </el-table-column>
                    <el-table-column label="配置" width="110" fixed="right">
                        <template #default="{ row }">
                            <el-button size="small" text @click="openEdit(row)">编辑</el-button>
                            <el-button size="small" text type="danger" @click="removeInstance(row)">移除</el-button>
                        </template>
                    </el-table-column>
                </el-table>

                <div class="fleet-empty-tip" v-if="!loading && rows.length === 0">
                    还没有登记任何设备。点「新增设备」登记机库或遥控器，或先按下「生成编排」得到多实例 compose 文件。
                </div>
                <div class="fleet-path-tip" v-if="configPath">
                    清单文件：{{ configPath }}
                </div>

                <!-- 批量结果 -->
                <div class="fleet-batch-result" v-if="batchResults.length">
                    <div class="fleet-batch-title">{{ batchLog }}</div>
                    <div v-for="item in batchResults" :key="item.id" class="fleet-batch-row">
                        <el-tag size="small" :type="item.success ? 'success' : 'danger'">
                            {{ item.success ? '成功' : '失败' }}
                        </el-tag>
                        <span class="fleet-batch-name">{{ item.name }}</span>
                        <span class="fleet-batch-msg">{{ item.message }}</span>
                    </div>
                </div>

                <!-- 新建并启动结果（含容器信息与逐步明细） -->
                <div class="fleet-batch-result" v-if="provisionResults.length">
                    <div class="fleet-batch-title">
                        新建结果
                        <el-button size="small" text @click="provisionResults = []">清空</el-button>
                    </div>
                    <div v-for="item in provisionResults" :key="item.id" class="fleet-bringup-item">
                        <div class="fleet-bringup-head">
                            <el-tag size="small" :type="item.success ? 'success' : 'warning'">
                                {{ item.success ? '完成' : '部分完成' }}
                            </el-tag>
                            <span class="fleet-batch-name">{{ item.name }}</span>
                            <span class="fleet-batch-msg">{{ item.message }}</span>
                        </div>
                        <div class="fleet-sub" v-if="item.containerName" style="padding-left:8px;margin-bottom:4px;">
                            容器：{{ item.containerName }}
                            <span v-if="item.hostPort">　宿主机端口：{{ item.hostPort }}</span>
                        </div>
                        <div v-for="(step, i) in item.steps" :key="i" class="fleet-bringup-step"
                             :style="step.skipped ? 'opacity:0.6;' : ''">
                            <span class="fleet-bringup-step-label">{{ step.label }}</span>
                            <el-tag size="small" :type="step.skipped ? 'info' : (step.success ? 'success' : 'danger')">
                                {{ step.skipped ? '跳过' : (step.success ? '通过' : '失败') }}
                            </el-tag>
                            <span class="fleet-bringup-step-detail">{{ step.detail }}</span>
                        </div>
                    </div>
                </div>

                <!-- 一键启用结果（逐步明细） -->
                <div class="fleet-batch-result" v-if="bringupResults.length">
                    <div class="fleet-batch-title">
                        一键启用结果
                        <el-button size="small" text @click="bringupResults = []">清空</el-button>
                    </div>
                    <div v-for="item in bringupResults" :key="item.id" class="fleet-bringup-item">
                        <div class="fleet-bringup-head">
                            <el-tag size="small" :type="item.success ? 'success' : 'danger'">
                                {{ item.success ? '成功' : '失败' }}
                            </el-tag>
                            <span class="fleet-batch-name">{{ item.name }}</span>
                            <span class="fleet-batch-msg">{{ item.message }}</span>
                        </div>
                        <div v-for="(step, i) in item.steps" :key="i" class="fleet-bringup-step"
                             :style="step.skipped ? 'opacity:0.6;' : ''">
                            <span class="fleet-bringup-step-label">{{ step.label }}</span>
                            <el-tag size="small" :type="step.skipped ? 'info' : (step.success ? 'success' : 'danger')">
                                {{ step.skipped ? '跳过' : (step.success ? '通过' : '失败') }}
                            </el-tag>
                            <span class="fleet-bringup-step-detail">{{ step.detail }}</span>
                        </div>
                    </div>
                </div>

                <!-- 新增/编辑弹窗 -->
                <el-dialog v-model="showForm" :title="editingId ? '编辑设备' : '新增设备（机库 / 遥控器）'" width="580px">
                    <el-form label-width="110px" size="small">
                        <!-- 新建时的核心选项：是否直接创建实例容器 -->
                        <el-form-item v-if="!editingId" label="创建方式">
                            <el-radio-group v-model="autoProvision" :disabled="!provisionerStatus.reachable">
                                <el-radio-button :label="true">新建并启动</el-radio-button>
                                <el-radio-button :label="false">仅登记（手动起容器）</el-radio-button>
                            </el-radio-group>
                            <div class="fleet-form-hint">
                                <template v-if="provisionerStatus.reachable">
                                    新建并启动：自动创建容器、连接 MQTT 并注册上线，一步到位
                                </template>
                                <template v-else-if="provisionerStatus.enabled">
                                    实例供给服务已配置但不可达，暂时只能登记；请检查 provisioner 容器状态
                                </template>
                                <template v-else>
                                    未部署实例供给服务（provisioner），只能登记清单；启动方式见文档
                                </template>
                            </div>
                        </el-form-item>
                        <el-form-item label="接入模式" required>
                            <el-radio-group v-model="form.deviceMode" @change="onFormDeviceModeChange">
                                <el-radio-button label="DOCK">机库上云</el-radio-button>
                                <el-radio-button label="PILOT">遥控器上云（单兵）</el-radio-button>
                            </el-radio-group>
                            <div class="fleet-form-hint">
                                <template v-if="form.deviceMode === 'PILOT'">
                                    遥控器作为网关（domain=2），飞行器为子设备（domain=0）；
                                    跳过机场注册流程，MQTT 连上后直接 update_topo 上线
                                </template>
                                <template v-else>
                                    机场作为网关（domain=3），走完整注册流程（config → 绑定 → 组织绑定）
                                </template>
                            </div>
                        </el-form-item>
                        <el-form-item label="设备名称" required>
                            <el-input v-model="form.name" :placeholder="form.deviceMode === 'PILOT' ? '如：M350 单兵机组' : '如：成都高新机库'"></el-input>
                            <div class="fleet-form-hint" v-if="autoProvision && !editingId">
                                容器名会由名称自动派生（中文名转为 dock-xxxx）
                            </div>
                        </el-form-item>
                        <el-form-item v-if="!autoProvision" label="控制台地址" required>
                            <div style="display:flex;gap:8px;width:100%;">
                                <el-input v-model="form.host" placeholder="127.0.0.1 或宿主机 IP / 容器名" style="flex:1;"></el-input>
                                <el-input-number v-model="form.port" :min="1" :max="65535" controls-position="right" style="width:130px;"></el-input-number>
                            </div>
                            <div class="fleet-form-hint">
                                单机部署填 127.0.0.1:9090；多实例按 compose 里的宿主机映射端口填写（9091/9092...）
                            </div>
                        </el-form-item>
                        <el-form-item v-else-if="!editingId" label="宿主机端口">
                            <el-input-number v-model="form.hostPort" :min="1" :max="65535"
                                             controls-position="right" style="width:150px;"
                                             placeholder="留空自动分配"></el-input-number>
                            <div class="fleet-form-hint">
                                留空由供给服务在允许范围内自动分配；创建成功后自动回填容器地址
                            </div>
                        </el-form-item>
                        <!-- 机库上云：机场机型 -->
                        <el-form-item v-if="form.deviceMode !== 'PILOT'" label="机场机型">
                            <el-select v-model="form.dockType" style="width:100%;" clearable placeholder="沿用实例当前机型">
                                <el-option v-for="item in models.dockTypes" :key="item.value"
                                           :label="item.label" :value="item.value"></el-option>
                            </el-select>
                        </el-form-item>
                        <!-- 遥控器上云：遥控器机型 -->
                        <el-form-item v-else label="遥控器机型">
                            <el-select v-model="form.controllerType" style="width:100%;" clearable
                                       placeholder="沿用实例当前机型" @change="onFormControllerTypeChange">
                                <el-option v-for="item in models.controllerTypes" :key="item.value"
                                           :label="item.label" :value="item.value">
                                    <span>{{ item.label }}</span>
                                    <span style="float:right;color:#94a3b8;font-size:11px;">{{ item.displayName }}</span>
                                </el-option>
                            </el-select>
                            <div class="fleet-form-hint">
                                遥控器是单兵模式的网关设备（domain=2），飞行器列表会按搭配关系联动
                            </div>
                        </el-form-item>
                        <el-form-item label="飞行器机型">
                            <el-select v-model="form.droneType" style="width:100%;" clearable placeholder="沿用实例当前机型">
                                <el-option v-for="item in formDroneTypeOptions" :key="item.value"
                                           :label="item.label" :value="item.value"></el-option>
                            </el-select>
                        </el-form-item>
                        <!-- 机库上云：机场 SN -->
                        <el-form-item v-if="form.deviceMode !== 'PILOT'" label="机场 SN">
                            <el-input v-model="form.dockSn" placeholder="留空则由实例自行生成唯一 SN"></el-input>
                            <div class="fleet-form-hint">
                                填固定值便于在平台侧提前预置机库记录；留空则使用实例生成的唯一 SN
                            </div>
                        </el-form-item>
                        <!-- 遥控器上云：遥控器 SN -->
                        <el-form-item v-else label="遥控器 SN">
                            <el-input v-model="form.controllerSn" placeholder="留空则由实例自行生成（机型默认 SN）"></el-input>
                            <div class="fleet-form-hint">
                                遥控器 SN 是 PILOT 模式的网关身份，平台侧 <code>dock</code> 表需存在
                                <code>kind='REMOTE_CONTROLLER'</code> 的同 SN 记录
                            </div>
                        </el-form-item>
                        <el-form-item label="飞行器 SN">
                            <el-input v-model="form.droneSn" placeholder="留空则由实例自行生成唯一 SN"></el-input>
                        </el-form-item>
                        <el-form-item :label="form.deviceMode === 'PILOT' ? '起始坐标' : '机场坐标'">
                            <div style="display:flex;gap:8px;width:100%;">
                                <el-input-number v-model="form.latitude" :precision="6" :step="0.0001"
                                                 placeholder="纬度" controls-position="right" style="flex:1;"></el-input-number>
                                <el-input-number v-model="form.longitude" :precision="6" :step="0.0001"
                                                 placeholder="经度" controls-position="right" style="flex:1;"></el-input-number>
                            </div>
                            <div class="fleet-form-hint">
                                {{ form.deviceMode === 'PILOT'
                                    ? '遥控器上云没有机场停靠点，这里作为飞行器初始位置（OSD 坐标起点）'
                                    : '各机库应拉开数公里，距离选库的推荐结果才有区分度' }}
                            </div>
                        </el-form-item>
                        <el-form-item label="海拔（米）">
                            <el-input-number v-model="form.height" :precision="1" :step="1"
                                             controls-position="right" style="width:160px;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="备注">
                            <el-input v-model="form.note" type="textarea" :rows="2" placeholder="可选"></el-input>
                        </el-form-item>
                        <el-form-item label="纳入批量">
                            <el-switch v-model="form.enabled"></el-switch>
                        </el-form-item>
                        <el-form-item v-if="!editingId && autoProvision" label="自动注册上线">
                            <el-switch v-model="form.autoRegister"></el-switch>
                            <div class="fleet-form-hint">
                                开启后创建完实例会立即连接 MQTT 并注册上线（使用上方「参数」里的连接配置）
                            </div>
                        </el-form-item>
                        <el-form-item label="当前实例">
                            <el-switch v-model="form.selfInstance"></el-switch>
                            <div class="fleet-form-hint">
                                勾选表示这台就是你现在访问的控制台。容器部署时对外端口是宿主机映射端口，
                                勾选后服务端会自动走容器内部回环访问，避免跨端口不可达
                            </div>
                        </el-form-item>
                    </el-form>
                    <template #footer>
                        <el-button size="small" @click="showForm = false">取消</el-button>
                        <el-button size="small" type="primary" :loading="saving" @click="saveForm">
                            {{ (!editingId && autoProvision) ? '新建并启动' : '保存' }}
                        </el-button>
                    </template>
                </el-dialog>

                <!-- compose 弹窗 -->
                <el-dialog v-model="showCompose" title="生成多实例编排（docker-compose-fleet.yml）" width="780px">
                    <el-form label-width="110px" size="small" inline>
                        <el-form-item label="镜像">
                            <el-input v-model="composeForm.image" style="width:280px;"></el-input>
                        </el-form-item>
                        <el-form-item label="MQTT 地址">
                            <el-input v-model="composeForm.mqttHost" style="width:200px;"></el-input>
                        </el-form-item>
                        <el-form-item label="端口">
                            <el-input-number v-model="composeForm.mqttPort" :min="1" :max="65535"
                                             controls-position="right" style="width:120px;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="用户名">
                            <el-input v-model="composeForm.mqttUsername" style="width:140px;"></el-input>
                        </el-form-item>
                        <el-form-item label="密码">
                            <el-input v-model="composeForm.mqttPassword" show-password style="width:140px;"></el-input>
                        </el-form-item>
                        <el-form-item label="外部网络">
                            <el-input v-model="composeForm.network" placeholder="留空则不加入外部网络"
                                      style="width:200px;"></el-input>
                        </el-form-item>
                        <el-form-item>
                            <el-button size="small" @click="loadCompose" :loading="composeLoading">重新生成</el-button>
                        </el-form-item>
                    </el-form>
                    <el-input v-model="composeText" type="textarea" :rows="18" readonly
                              class="fleet-compose-text"></el-input>
                    <template #footer>
                        <el-button size="small" @click="copyCompose">复制</el-button>
                        <el-button size="small" type="primary" @click="downloadCompose">下载</el-button>
                        <el-button size="small" @click="showCompose = false">关闭</el-button>
                    </template>
                </el-dialog>

                <!-- 选库排序弹窗 -->
                <el-dialog v-model="showCandidates" title="选库排序（按到目标点的距离）" width="820px">
                    <div class="fleet-form-hint" style="margin-bottom:12px;">
                        输入目标点坐标（用户圈选区域中心或目标点位），按直线距离排序并标出能否派遣。
                        完整选库权重策略（电量权重、空域限制、跨库接力等）属于平台业务，这里只提供模拟器可确证的数据。
                    </div>
                    <el-form label-width="90px" size="small" inline>
                        <el-form-item label="目标纬度">
                            <el-input-number v-model="candidateTarget.latitude" :precision="6" :step="0.0001"
                                             controls-position="right" style="width:170px;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="目标经度">
                            <el-input-number v-model="candidateTarget.longitude" :precision="6" :step="0.0001"
                                             controls-position="right" style="width:170px;"></el-input-number>
                        </el-form-item>
                        <el-form-item label="返回条数">
                            <el-input-number v-model="candidateTarget.limit" :min="0" :max="100"
                                             controls-position="right" style="width:110px;"></el-input-number>
                        </el-form-item>
                        <el-form-item>
                            <el-button size="small" type="primary" :loading="candidateLoading"
                                       @click="loadCandidates">计算排序</el-button>
                            <el-button size="small" text @click="loadStatuses(); loadCandidates()">刷新</el-button>
                        </el-form-item>
                    </el-form>

                    <div v-if="candidateError" style="color:#f56c6c;font-size:12px;margin-bottom:8px;">
                        {{ candidateError }}
                    </div>

                    <el-table :data="candidates" size="small" border stripe style="width:100%">
                        <el-table-column label="#" type="index" width="45"></el-table-column>
                        <el-table-column label="机库" min-width="130">
                            <template #default="{ row }">
                                <div class="fleet-name">{{ row.name }}</div>
                                <div class="fleet-mono fleet-sub">{{ row.dockSn || '-' }}</div>
                            </template>
                        </el-table-column>
                        <el-table-column label="距离" width="110" sortable
                                         :sort-method="(a, b) => (a.distanceMeters || Infinity) - (b.distanceMeters || Infinity)">
                            <template #default="{ row }">
                                <span class="fleet-mono">{{ distanceText(row.distanceMeters) }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="坐标" min-width="160">
                            <template #default="{ row }">
                                <span class="fleet-mono">{{ coordText(row.latitude, row.longitude) }}</span>
                            </template>
                        </el-table-column>
                        <el-table-column label="电量" width="80">
                            <template #default="{ row }">
                                {{ row.batteryPercent != null ? row.batteryPercent + '%' : '-' }}
                            </template>
                        </el-table-column>
                        <el-table-column label="可派遣" width="150">
                            <template #default="{ row }">
                                <el-tag size="small" :type="row.dispatchable ? 'success' : 'info'">
                                    {{ row.dispatchable ? '可派遣' : '不可' }}
                                </el-tag>
                                <div class="fleet-sub" v-if="row.rejectReason">{{ row.rejectReason }}</div>
                            </template>
                        </el-table-column>
                    </el-table>

                    <div v-if="!candidates.length && !candidateLoading" class="fleet-empty-tip" style="margin-top:10px;">
                        暂无结果，填写目标坐标后点击「计算排序」
                    </div>
                </el-dialog>

                <!-- 平台预置弹窗 -->
                <el-dialog v-model="showPlatformSeed" title="平台侧预置数据（dock / device）" width="900px">
                    <div class="fleet-form-hint" style="margin-bottom:12px;">
                        平台在 airport_organization_bind 阶段只允许「已知网关」绑定，
                        因此每个模拟机库上线前必须先在平台侧存在对应 dock / device 记录。
                        下面的数据由当前清单生成，可直接调用平台 API 或执行 SQL。
                    </div>
                    <el-form label-width="90px" size="small" inline>
                        <el-form-item label="组织 ID">
                            <el-input v-model="platformSeed.organizationId" style="width:290px;"
                                      placeholder="留空使用默认组织"></el-input>
                        </el-form-item>
                        <el-form-item label="品牌">
                            <el-input v-model="platformSeed.brand" style="width:100px;"></el-input>
                        </el-form-item>
                        <el-form-item>
                            <el-button size="small" @click="loadPlatformSeed" :loading="platformSeedLoading">重新生成</el-button>
                        </el-form-item>
                    </el-form>

                    <div v-if="platformSeedData.skipped.length" class="fleet-seed-skipped">
                        <div style="font-weight:600;font-size:12px;margin-bottom:4px;">以下机库未生成（需先固定 SN）：</div>
                        <div v-for="(item, i) in platformSeedData.skipped" :key="i" class="fleet-sub">· {{ item }}</div>
                    </div>

                    <el-tabs>
                        <el-tab-pane :label="'JSON（' + (platformSeedData.docks.length + platformSeedData.devices.length) + ' 条）'">
                            <div class="fleet-sub" style="margin-bottom:6px;">
                                docks → POST /api/docks；devices → POST /api/devices
                            </div>
                            <el-input type="textarea" :rows="16" readonly class="fleet-compose-text"
                                      :model-value="JSON.stringify({ docks: platformSeedData.docks, devices: platformSeedData.devices }, null, 2)"></el-input>
                        </el-tab-pane>
                        <el-tab-pane label="SQL（可直接执行）">
                            <el-input type="textarea" :rows="16" readonly class="fleet-compose-text"
                                      v-model="platformSeedData.sql"></el-input>
                        </el-tab-pane>
                    </el-tabs>

                    <template #footer>
                        <el-button size="small" @click="copyPlatformSql">复制 SQL</el-button>
                        <el-button size="small" type="primary" @click="downloadPlatformSql">下载 SQL 文件</el-button>
                        <el-button size="small" @click="showPlatformSeed = false">关闭</el-button>
                    </template>
                </el-dialog>
            </div>
        `
    });
}
