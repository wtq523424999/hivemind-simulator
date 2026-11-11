/**
 * 单兵无人机能力面板（遥控器上云专属）
 *
 * 展示可搭配的单兵无人机清单及其行为属性（domain/type/sub_type、默认负载、
 * OSD/State Builder、可用能力），并高亮当前运行配置。
 *
 * 数据来源：GET /api/pilot/capabilities —— 由后端从 SDK 枚举与 Builder 支持矩阵推导，
 * 前端不维护静态机型表，避免 SDK 升级后与实际设备行为漂移。
 *
 * 依赖：本文件需在主 app 脚本之前加载，并由主 app 调用 registerPilotFleetPanel(app) 注册。
 */
function registerPilotFleetPanel(app) {
    const { ref, computed, onMounted, inject } = Vue;

    app.component('pilot-fleet', {
        setup() {
            const ctx = inject('ctx');
            const { fetchJSON, connConfig } = ctx;

            const loading = ref(false);
            const data = ref(null);

            const load = async () => {
                loading.value = true;
                try {
                    data.value = await fetchJSON('/api/pilot/capabilities');
                } finally {
                    loading.value = false;
                }
            };

            // 只展示当前所选遥控器可搭配的飞机；未选择遥控器时展示全部
            const visibleDrones = computed(() => {
                const all = (data.value && data.value.drones) || [];
                const controller = (data.value && data.value.controllers || [])
                    .find(c => c.key === connConfig.controller_type);
                if (!controller) return all;
                return all.filter(d => controller.droneKeys.includes(d.key));
            });

            const currentDrone = computed(() => (data.value && data.value.current && data.value.current.droneType) || '');
            const isPilot = computed(() => connConfig.device_mode === 'PILOT');
            const limitations = computed(() => (data.value && data.value.limitations) || []);

            const payloadText = (d) => {
                const p = d.defaultPayload || {};
                if (!p.cameraIndex) return '-';
                return `${p.key || '-'}（${p.cameraIndex}${p.thermal ? '，热成像' : ''}）`;
            };
            const modelKeyText = (d) => `${d.domain}-${d.type}-${d.subType}`;

            onMounted(load);

            return {
                loading, data, visibleDrones, currentDrone, isPilot, limitations,
                payloadText, modelKeyText, load,
            };
        },
        template: `
            <el-card class="right-card">
                <template #header>
                    <div style="display:flex;justify-content:space-between;align-items:center;">
                        <span>单兵无人机（{{ visibleDrones.length }}）</span>
                        <el-button size="small" text @click="load" :loading="loading">刷新</el-button>
                    </div>
                </template>

                <div v-if="!isPilot" style="color:#909399;font-size:12px;line-height:1.6;">
                    当前为机场上云模式。切换到「遥控器上云（单兵）」后，此处显示可搭配的单兵无人机。
                </div>

                <template v-else>
                    <div v-if="visibleDrones.length === 0" style="color:#909399;font-size:12px;">
                        {{ loading ? '加载中...' : '暂无数据' }}
                    </div>

                    <div v-for="d in visibleDrones" :key="d.key"
                         style="border:1px solid #e4e7ed;border-radius:4px;padding:8px;margin-bottom:8px;"
                         :style="d.key === currentDrone ? 'border-color:#409eff;background:#f0f7ff;' : ''">
                        <div style="display:flex;justify-content:space-between;align-items:center;">
                            <span style="font-size:13px;font-weight:600;">{{ d.label }}</span>
                            <el-tag v-if="d.key === currentDrone" type="primary" size="small">当前</el-tag>
                        </div>
                        <div style="font-size:12px;color:#606266;line-height:1.7;margin-top:4px;">
                            <div>型号标识：{{ modelKeyText(d) }}　SN：{{ d.defaultSn }}</div>
                            <div>默认负载：{{ payloadText(d) }}</div>
                            <div>OSD 字段集：{{ d.osdBuilder || '未实现' }}　State：{{ d.stateBuilder || '未实现' }}</div>
                        </div>
                        <div style="margin-top:6px;">
                            <el-tag v-for="(c, i) in d.capabilities" :key="i" size="small" type="info"
                                    style="margin:2px 4px 2px 0;">{{ c }}</el-tag>
                        </div>
                    </div>

                    <div v-if="limitations.length" style="margin-top:8px;padding-top:8px;border-top:1px dashed #e4e7ed;">
                        <div style="font-size:12px;color:#909399;margin-bottom:4px;">单兵模式边界</div>
                        <div v-for="(l, i) in limitations" :key="i" style="font-size:12px;color:#909399;line-height:1.6;">
                            · {{ l }}
                        </div>
                    </div>
                </template>
            </el-card>
        `
    });
}
