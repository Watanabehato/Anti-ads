package com.antiads.probe.diag

/** 诊断页的类型集合与对照选择（纯逻辑，便于单测）。 */
object ProbeTypes {

    /** 与产品默认拦截集合一致的候选（加速度计/陀螺仪/重力/线性加速度/旋转矢量）。 */
    val DEFAULT_SELECTED: List<Int> = listOf(1, 4, 9, 10, 11)

    /** 实验页最多显示 5 种配置支持类型（contracts 第 8 节）。 */
    const val MAX_SELECTED: Int = 5

    /**
     * 优先选作"未选对照"的连续上报类型：磁场通常稳定可用；
     * 光线/气压/温度等按变化上报的类型只在设备确无稳定对照时才使用。
     */
    val PREFERRED_CONTROL_TYPES: List<Int> = listOf(2, 6, 13, 12, 5, 8, 3)

    /**
     * 选择对照类型：必须是设备真实存在、且不在拦截集合内的类型；否则返回 null（页面上显式写"无对照"）。
     */
    fun chooseControlType(available: Set<Int>, excluded: Set<Int>): Int? {
        PREFERRED_CONTROL_TYPES.firstOrNull { it in available && it !in excluded }?.let { return it }
        return available.filter { it !in excluded }.minOrNull()
    }

    fun displayName(sensorType: Int): String = when (sensorType) {
        1 -> "加速度计"
        2 -> "磁场"
        3 -> "方向(已废弃)"
        4 -> "陀螺仪"
        5 -> "光线"
        6 -> "气压"
        8 -> "距离"
        9 -> "重力"
        10 -> "线性加速度"
        11 -> "旋转矢量"
        12 -> "湿度"
        13 -> "温度"
        else -> "类型$sensorType"
    }

    /** 代码中出现的判定用类型常量，避免魔法数字散落。 */
    object Types {
        const val ACCELEROMETER: Int = 1
        const val MAGNETIC_FIELD: Int = 2
    }
}
